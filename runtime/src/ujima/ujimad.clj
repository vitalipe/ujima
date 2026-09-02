(ns ujima.ujimad
  (:require [lib.io                  :as io]
            [ujima.log               :as log]

            [ujima.device         :as device]
            [ujima.device.ab      :as ab]
            [ujima.control        :as control]
            [lib.shell :as shell]

            [lib.http       :as http]
            [ujima.api      :as api]
            [ujima.api.auth :as auth]

            [ujima.desktop          :as desktop]
            [ujima.desktop.http     :as shell-http]
            [ujima.desktop.http.converge :as shell-http-converge]
            [ujima.desktop.app      :as app]
            [ujima.desktop.app.catalog.loader :as loader]
            [ujima.storage          :as storage]
            [ujima.events           :as events]))




(defn -main [& args]

  (let [env         (io/slurp-config "config" "ujimad")
        deploy      (io/slurp-config "config" "env")     ; image facts; hosts see env.dev.edn
        disk        (device/system->disk)                ; nil on hosts, FIXME: make autodetect fallback, put in config
        app-cfg     (get-in env [:desktop :app])
        app-catalog (loader/load-catalog (:catalog app-cfg) (:fallback-icon app-cfg))
        api-http    (get-in env [:api :http] {})
        ui-http     (get-in env [:desktop :http] {})]


    (shell/install-remap! (get-in env [:shell :commands] {}))
    (log/init!            (get-in env [:log] {:level :info}))

    (device/init! disk)   ; machine reality first: the disk stamp

    ;; the planes; events/init! connects them
    (shell-http-converge/init!)
    (control/init! (get-in env [:control] {}))
    (desktop/await-x!)

    (app/init! (merge (select-keys app-cfg [:open-web-app-bin :serve-web-app-bin])
                      {:catalog app-catalog}))

    (storage/init! (get-in env [:storage] {}))

    ;; every arrow, then the boot converge — before anything serves
    (events/init! (get-in env [:events] {}))
    (control/converge-fresh!)

    (let [{system-disk-id :system-disk-id :as disk-info} (ab/ujima-disk-info disk)

          boot-rt      (device/system->boot-runtime) 
          auth-cfg     (merge (get-in env [:api :auth] {})
                              {:key     (:effective (control/setting [:circle :token]))
                               :self-id system-disk-id})]

      (http/listen! (merge api-http
                           {:endpoints {"api" (api/endpoints {:version      (:version deploy)
                                                              :id           system-disk-id
                                                              :gate         (auth/->gate auth-cfg)
                                                              :disk         disk-info
                                                              :slot         (ab/running-slot boot-rt)})}
                            :log       log/log!
                            :sign      (auth/->sign auth-cfg)})))

    (http/listen! (merge ui-http
                         {:endpoints {"ujima-desktop" (shell-http/endpoints ui-http)}
                          :log       log/log!}))

    (try
      (desktop/init!! (get-in env [:desktop] {}))
      (catch Throwable e (log/error "shell died" {:error (ex-message e)})))

    (System/exit 1)))
