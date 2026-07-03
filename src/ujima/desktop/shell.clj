(ns ujima.desktop.shell
  (:require [lib.throttle           :refer [throttle-leading-trailing]]
            [ujima.log              :as log]
            [ujima.control.commands :as commands]))


;; the throttle delivers f's outcome into per-call promises; nobody derefs them on
;; the fire-and-forget move path, so a bare set-volume! would fail silently — warn
;; here (an unplugged sink mid-drag must show up in the journal)
(defonce ^:private set-volume-throttled!
  (throttle-leading-trailing 250
    (fn [value]
      (try (commands/set-volume! value)
           (catch Exception e
             (log/warn "volume move dropped" {:value value :error (ex-message e)}))))))


(defn volume-moved!
  "Record a slider position; returns immediately.

   Applies the first value immediately, coalesces intermediate drag values,
   and guarantees that the final dragged value is applied."
  [value]
  (when-not (number? value)
    (throw (ex-info "volume must be a number"
                    {:error :request/malformed
                     :value value})))
  (set-volume-throttled! value)
  nil)
