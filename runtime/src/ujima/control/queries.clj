(ns ujima.control.queries
  "Read-side projections over the effective settings — pure, the caller reads.
   The [:audio :active] setting IS the truth for \"which output\".")


(defn audio-status
  "Volume is nil when no output is active (widgets grey out)."
  [settings]
  (let [output (get settings [:audio :active])]
    {:volume (when output (get settings [:audio output :volume]))
     :muted  (get settings [:audio :muted])
     :output output}))


(defn keyboard-status
  "Domain facts only — the switcher's cycle order lives in the UI projection."
  [settings]
  {:layout  (get settings [:keyboard :layout])
   :layouts (get settings [:keyboard :available-layouts])})
