(ns ujima.events.audio
  "Audio device policy: what [:audio :active] should be as sinks come and go.
   Decisions only — the watching is linux.audio/watch-sinks!, the listener
   thread is ujima.events. Rules: a NEW class wins (plugging headphones means
   you want them); a vanished active falls back by class priority; a baseline
   event (before = classes) keeps a valid existing choice — a ujimad restart
   must not re-decide over a session's pick."
  (:require [clojure.set :as set]
            [ujima.log   :as log]
            [ujima.control          :as control]
            [ujima.control.commands :as commands]))


(def ^:private priority [:usb :hdmi])


(defn pick-active
  "Pure policy: the :active class given the previous and current class sets and
   the currently selected class."
  [before now current]
  (let [new (set/difference now before)]
    (cond
      (seq new)                   (first (filter new priority))
      (and current (now current)) current
      :else                       (first (filter now priority)))))


(defn on-sinks-changed!
  "Handle one sink-presence event: re-select and (re)write [:audio :active] —
   same value included, because the idempotent write still converges, which is
   what re-applies state onto a swapped device of the same class."
  [{:keys [before classes]}]
  (let [current (get (control/settings) [:audio :active])
        active  (pick-active before classes current)]
    (log/info "audio devices changed" {:present classes :active active})
    (commands/change-active-output! active :session)))
