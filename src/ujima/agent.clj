(ns ujima.agent
  (:require [clojure.core.async      :as async]
            [ujima.log               :as log]

            [ujima.linux.token :as token]
            [ujima.linux.audio :as audio]

            [ujima.agent.token :as token-events]
            [ujima.agent.audio :as audio-events]))


(defn- listen!
  "Drain a watcher channel into its handler, forever; a handler failure is
   logged and never kills the listener."
  [ch handle!]
  (async/thread
    (loop []
      (when-let [event (async/<!! ch)]
        (try (handle! event)
             (catch Throwable e
               (log/error "agent: event handler failed" {:error (ex-message e)})))
        (recur)))))


(defn init! [cfg]
  (let [control-token-ch* (token/watch-control-token!)
        sink-events-ch    (audio/watch-sinks! {:interval-ms (:audio-poll-ms cfg 1000)})]

    (log/info "Starting Agent loop")

    ;; device policy: keeps [:audio :active] aligned with plugged hardware
    (listen! sink-events-ch audio-events/on-sinks-changed!)

    ;; watch for events (bg thread); init! returns — the main thread goes on to hold the shell
    (async/thread
      (loop [prv-token nil]
        (when-let [token (async/<!! control-token-ch*)] ;; chan still open?
          (when (not= prv-token token)
            (token-events/on-control-token-change! token))
          (recur token))))))
