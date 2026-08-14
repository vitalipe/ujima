(ns ujima.ujimad
  (:require [lib.io                  :as io]
            [ujima.log               :as log]

            [ujima.device         :as device]
            [ujima.control        :as control]
            [ujima.linux.converge :as linux]
            [lib.shell :as shell]

            [lib.http  :as http]
            [ujima.api :as api]

            [ujima.desktop          :as desktop]
            [ujima.desktop.eww      :as eww]
            [ujima.desktop.http     :as shell-http]
            [ujima.desktop.http.converge :as shell-http-converge]
            [ujima.desktop.app      :as app]
            [ujima.events           :as events]))




(defn -main [& args]

  (let [env         (io/slurp-config "config" "ujimad")
        deploy      (io/slurp-edn "config/env.edn")   ; deploy-stamped facts (nil on hosts)
        app-cfg     (get-in env [:desktop :app])
        app-catalog (app/load-catalog (:catalog app-cfg) (:fallback-icon app-cfg))
        http-cfg    (get-in env [:http] {})]


    (shell/install-remap! (get-in env [:shell :commands] {}))
    (log/init!            (get-in env [:log] {:level :info}))

    (shell-http-converge/init!)
    (control/init!     (merge (get-in env [:control] {})
                              {:converge-targets [linux/converge! shell-http-converge/converge-ui!]}))
    (desktop/await-x!)
    (control/converge-fresh!)

    (app/init! (merge (select-keys app-cfg [:open-web-app-bin :serve-web-app-bin])
                      {:catalog app-catalog :converge-targets [shell-http-converge/converge-apps! eww/converge!]}))

    (events/init! (get-in env [:events] {}))

    ;; the machine edge: ujimad composes the tiers, the edge knows neither's vocabulary
    (http/listen! (merge http-cfg
                         {:endpoints {"api" (api/endpoints        {:version (:version deploy)})
                                      ""    (shell-http/endpoints (get-in env [:desktop :http] {}))}
                          :log       log/log!}))

    (try
      (desktop/init!! (get-in env [:desktop] {}))
      (catch Throwable e (log/error "shell died" {:error (ex-message e)})))

    (System/exit 1)))
