(ns ujima.control.queries
  "Read-side projections over the settings records — pure, the caller reads."
  (:require [lib.util              :refer [map-vals next-of]]
            [schema.ujima.settings :as defs]))


(defn public-settings
  "Settings a surface may be shown — a :secret? setting is absent, not masked."
  [settings]
  (let [secrets (into #{} (comp (filter :secret?) (map :key)) defs/settings)]
    (apply dissoc settings secrets)))


(defn audio-status
  "Volume is nil when no output is active (widgets grey out)."
  [settings]
  (let [settings (map-vals :effective settings)
        output   (settings [:audio :active])]
    {:volume (when output (settings [:audio output :volume]))
     :muted  (settings [:audio :muted])
     :output output}))


(defn ordered-layouts
  "Available layouts rotated so the CURRENT one is last — the switcher's order."
  [settings]
  (let [settings        (map-vals :effective settings)
        available       (settings [:keyboard :available-layouts])
        current         (settings [:keyboard :layout])
        [before after]  (split-with #(not= current %) available)]
    (if (seq after)
      (vec (concat (rest after) before [current]))
      (vec available))))


(defn next-keyboard-layout
  "The switcher's cycle order — one home, so the stream and the one-shot verb
   cannot drift."
  [settings]
  (let [settings (map-vals :effective settings)]
    (next-of (settings [:keyboard :available-layouts])
             (settings [:keyboard :layout]))))


(defn settings->tree
  "Records keyed by path vector -> the nested tree the query routes address."
  [settings]
  (reduce-kv assoc-in {} settings))
