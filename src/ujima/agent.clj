(ns ujima.agent
  (:require [clojure.core.async      :as async]
            [ujima.log               :as log]

            [ujima.control :as control]
            [ujima.linux.token :as token]

            [ujima.agent.events   :as events]))


(defn init! [env]
  (let [control-token-ch* (token/watch-control-token!)]
    
    (log/info "Starting Agent loop")

    ;; reconcile persistent settings
    (control/reconcile!!)

    ;; watch for events
    (async/thread
      (loop [prv-token nil]
        (when-let [token (async/<!! control-token-ch*)] ;; chan still open?
          (when (not= prv-token token)
            (events/on-control-token-change! runtime* token))
          (recur token))))

    ;; block
    @(promise)))

