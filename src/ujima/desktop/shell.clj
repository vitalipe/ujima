(ns ujima.desktop.shell
  "The /shell widget edge: verbs shaped for the eww widgets (not the eww process —
   that's ujima.desktop). A slider drag arrives as a stream of /volume/move values
   with no mouseup event; the debouncer below emulates one — after settle-ms of
   quiet, the LAST value becomes ONE control.commands call. Discrete verbs pass
   straight through and return the fresh resource so a widget updates itself from
   the response. The widget push/stream layer will accumulate here later."
  (:require [ujima.log              :as log]
            [ujima.control.commands :as commands])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))


;; drag events arrive every ~20-50ms while the finger moves, so this much quiet
;; reliably means "released"; it is also the floor of click-to-apply latency.
(def ^:private settle-ms 250)

(defonce ^:private moves (LinkedBlockingQueue.))


(defn volume-moved!
  "Record a slider position; returns immediately (fire-and-forget). The debouncer
   applies the last one once the stream goes quiet."
  [value]
  (when-not (number? value)
    (throw (ex-info "volume must be a number" {:error :request/malformed :value value})))
  (.offer moves value))


(defn- debounce-loop! []
  (loop [pending nil]
    (if-some [v (.poll moves settle-ms TimeUnit/MILLISECONDS)]
      (recur v)                                                ; still dragging — newest wins
      (do (when (some? pending)
            (try (commands/set-volume! pending)                ; the synthetic mouseup
                 (catch Exception e
                   (log/warn "volume move dropped" {:value pending :error (ex-message e)}))))
          (recur nil)))))


(defn start!
  "Start the volume debouncer (process-lifetime; dies with the session)."
  []
  (future (debounce-loop!))
  (log/info "desktop widget edge up" {:volume-settle-ms settle-ms}))


(defn toggle-mute!
  "Set mute to the widget's DESIRED state (idempotent — a lost request can't flip
   icon vs reality); returns the fresh audio resource."
  [muted]
  (commands/set-mute! muted)
  (commands/audio-status))


(defn next-layout!
  "Advance to the next available layout; returns the fresh keyboard resource."
  []
  (commands/next-layout!)
  (commands/keyboard-status))
