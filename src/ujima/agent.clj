(ns ujima.agent
  (:require [clojure.core.async      :as async]
            [ujima.env               :as env]
            [ujima.log               :as log]

            [ujima.device            :refer [->runtime]]

            [ujima.runtime.protocol  :as runtime]
            [ujima.runtime.settings  :refer [reconcile-settings!]]

            [ujima.agent.events      :as events]
            [ujima.agent.http        :as http]))


(defn init! [env runtime*]
  (let [control-token-ch* (runtime/watch-control-token! runtime*)]
    
    (log/info "Starting")

    ;; reconcile persistent settings
    (reconcile-settings! runtime* (runtime/settings runtime*))

    ;; watch for events
    (async/thread
      (loop [prv-token nil]
        (when-let [token (async/<!! control-token-ch*)] ;; chan still open?
          (when (not= prv-token token)
            (events/on-control-token-change! runtime* token))
          (recur token))))))


(defn -main [& args]

  (env/init! ["config/ujima.edn"
              "config/config.local.edn"])

  ;; first set log level
  (log/set-log-level! (env/get-in-env [:log :level] :info))


  (let [runtime* (->runtime (env/get-in-env [:runtime] {}))]
    (init! (env/get-in-env [:agent] {}) runtime*)
    (http/start! (env/get-in-env [:http] {}) runtime*))

  ;; block
  @(promise))

