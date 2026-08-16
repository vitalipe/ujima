(ns ujima.control.queries
  "Read-side projections over the settings records — pure, the caller reads.
   The [:audio :active] setting IS the truth for \"which output\".")


(defn- effective [settings key] (:effective (get settings key)))


(defn audio-status
  "Volume is nil when no output is active (widgets grey out)."
  [settings]
  (let [output (effective settings [:audio :active])]
    {:volume (when output (effective settings [:audio output :volume]))
     :muted  (effective settings [:audio :muted])
     :output output}))


(defn keyboard-status
  "Domain facts only — the switcher's cycle order lives in the UI projection."
  [settings]
  {:layout  (effective settings [:keyboard :layout])
   :layouts (effective settings [:keyboard :available-layouts])})


(defn settings->tree
  "Records keyed by path vector -> the nested tree the query routes address."
  [settings]
  (reduce-kv assoc-in {} settings))
