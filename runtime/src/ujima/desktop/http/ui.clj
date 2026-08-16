(ns ujima.desktop.http.ui
  "The /ui verbs that are not a stream: the switcher's next layout as a one-shot
   read, and volume moves, where interaction is not state."
  (:require [lib.throttle :refer [throttle-leading-trailing]]
            [ujima.log          :as log]
            [ujima.control          :as control]
            [ujima.control.queries  :as queries]
            [ujima.control.commands :as commands]))


;; --- volume moves (interaction ≠ state) -------------------------------------

;; the throttle delivers f's outcome into per-call promises; nobody derefs them on
;; the fire-and-forget move path, so a bare change-current-volume! would fail
;; silently — warn here (an unplugged sink mid-drag must show up in the journal)
(defonce ^:private change-volume-throttled!
  (throttle-leading-trailing 250
    (fn [value]
      (try (commands/change-current-volume! value :session)
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
  (change-volume-throttled! value)
  nil)


(defn keyboard-next
  "The switcher's next layout, so the keybind needn't tap the state stream."
  []
  {:next (queries/next-keyboard-layout (control/settings))})

