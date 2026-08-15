(ns ujima.events.clock
  (:require [ujima.log :as log]
            [ujima.control.commands :as commands]))


(def ^:private floor-key [:system :clock :epoch-floor])


(defn- save! []
  (commands/change-setting! floor-key (System/currentTimeMillis) :device))


(defn init!
  "The floor heartbeat — the software RTC's tick: record witnessed time now
   and every SAVE-MS."
  [{:keys [clock-save-ms] :or {clock-save-ms 600000}}]
  (future
    (loop []
      (try (save!)
           (catch Throwable e
             (log/warn "clock heartbeat failed" {:error (ex-message e)})))
      (Thread/sleep clock-save-ms)
      (recur))))
