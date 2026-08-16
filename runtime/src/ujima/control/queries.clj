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


(defn settings->tree
  "Records keyed by path vector -> the nested tree the query routes address."
  [settings]
  (reduce-kv assoc-in {} settings))
