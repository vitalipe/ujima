(ns ujima.desktop.app.procs
  "The PROC plane: the spawn registry {app-id -> {:handle :pid :spawned-at
   :windowed?}} — what WE did, plus one resolved fact: a window has been seen
   (so a later windowless moment reads :closed, never :new again). Judgments
   pure; the live atom is ujima.desktop.app's.")


(defn mark-windowed
  "Registered apps in PRESENT-IDS have windowed."
  [registry present-ids]
  (reduce (fn [r id] (cond-> r (get r id) (assoc-in [id :windowed?] true)))
          registry
          present-ids))


(defn awaiting?
  "Spawned, never windowed."
  [registry id]
  (when-let [e (get registry id)]
    (not (:windowed? e))))
