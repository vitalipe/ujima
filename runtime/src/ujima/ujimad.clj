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
            [ujima.desktop.http.ui  :as ui]
            [ujima.desktop.http.app :as apps]
            [ujima.desktop.app      :as app]
            [ujima.events           :as events]))




(defn -main [& args]

  (let [env         (io/slurp-config "config" "ujimad")
        app-catalog (app/load-catalog (get-in env [:desktop :app :catalog]))
        http-cfg    (get-in env [:desktop :http] {})]


    (shell/install-remap! (get-in env [:shell :commands] {}))

    (log/init!         (get-in env [:log] {:level :info}))
    (control/init!     (merge (get-in env [:control] {})
                              {:converge-targets [linux/converge! ui/converge!]}))
    (desktop/await-x!)
    (control/converge-fresh!)

    (app/init! {:catalog app-catalog :converge-targets [apps/converge! eww/converge!]})

    (events/init! (get-in env [:events] {}))

    ;; the machine edge: ujimad composes the tiers, the edge knows neither's vocabulary
    (http/listen! (merge http-cfg
                         {:handlers [api/handler (shell-http/handler http-cfg)]
                          :errors   (merge api/error-status shell-http/error-status)
                          :log      log/log!}))

    (try
      (desktop/init!! (get-in env [:desktop] {}))
      (catch Throwable e (log/error "shell died" {:error (ex-message e)})))

    (System/exit 1)))
