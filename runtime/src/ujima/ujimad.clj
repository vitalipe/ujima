(ns ujima.ujimad
  (:require [lib.io                  :as io]
            [ujima.log               :as log]

            [ujima.device         :as device]
            [ujima.device.ab      :as ab]
            [ujima.control        :as control]
            [ujima.linux.converge :as linux]
            [lib.shell :as shell]

            [lib.http       :as http]
            [ujima.api      :as api]
            [ujima.api.auth :as auth]

            [ujima.desktop          :as desktop]
            [ujima.desktop.eww      :as eww]
            [ujima.desktop.http     :as shell-http]
            [ujima.desktop.http.converge :as shell-http-converge]
            [ujima.desktop.app      :as app]
            [ujima.storage          :as storage]
            [ujima.events.token     :as token-events]
            [ujima.events           :as events]))




(defn -main [& args]

  (let [env         (io/slurp-config "config" "ujimad")
        deploy      (io/slurp-config "config" "env")     ; image facts; hosts see env.dev.edn
        disk        (device/system->disk)                ; nil on hosts, FIXME: make autodetect fallback, put in config
        app-cfg     (get-in env [:desktop :app])
        app-catalog (app/load-catalog (:catalog app-cfg) (:fallback-icon app-cfg))
        api-http    (get-in env [:api :http] {})
        ui-http     (get-in env [:desktop :http] {})]


    (shell/install-remap! (get-in env [:shell :commands] {}))
    (log/init!            (get-in env [:log] {:level :info}))

    (shell-http-converge/init!)
    (control/init!     (merge (get-in env [:control] {})
                              {:converge-targets [linux/converge! shell-http-converge/converge-ui!]}))
    (desktop/await-x!)
    (control/converge-fresh!)

    (app/init! (merge (select-keys app-cfg [:open-web-app-bin :serve-web-app-bin])
                      {:catalog app-catalog :converge-targets [shell-http-converge/converge-apps! eww/converge!]}))

    ;; the console follows a circle token on removable storage
    (storage/init! (merge (get-in env [:storage] {})
                          {:converge-targets [token-events/on-storage!]}))

    (when disk
      (ab/system-disk-id! disk)) ; first boot stamps here


    (let [{system-disk-id :system-disk-id :as disk-info} (when disk (ab/ujima-disk-info disk))
          auth-cfg (merge (get-in env [:api :auth] {})
                          {:key     (:effective (control/setting [:circle :token]))
                           :self-id system-disk-id})]

      (http/listen! (merge api-http
                           {:endpoints {"api" (api/endpoints {:version (:version deploy)
                                                              :id      system-disk-id
                                                              :gate    (auth/->gate auth-cfg)
                                                              :disk    disk-info})}
                            :log       log/log!
                            :sign      (auth/->sign auth-cfg)})))

    (http/listen! (merge ui-http
                         {:endpoints {"ujima-desktop" (shell-http/endpoints ui-http)}
                          :log       log/log!}))

    ;; the taps, last: a handler may touch any surface above — the console needs /api serving
    (events/init! (get-in env [:events] {}))

    (try
      (desktop/init!! (get-in env [:desktop] {}))
      (catch Throwable e (log/error "shell died" {:error (ex-message e)})))

    (System/exit 1)))
