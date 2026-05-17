(ns ujima.agent
  (:require [clojure.core.async      :as async]
            [ujima.log               :as log]

            [ujima.runtime.protocol  :as runtime]
            [ujima.runtime.settings  :refer [reconcile-settings!]]

            [ujima.agent.events      :as    events]))


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

