(ns ujima.desktop.app.catalog
  "app.edn launch specs indexed by :id, validated loudly. Pure.")


(defn validate-app!
  "Throw (ex-info, naming the offending field) unless APP is a launchable spec: a map with
   :id, :label and a non-empty :exec vector. The one definition of app validity — the boot
   scanner calls it per app (log-and-skip), ->catalog re-runs it as a final assertion."
  [{:keys [id label exec] :as app}]
  (when-not (map? app) (throw (ex-info "app spec is not a map" {:app app})))
  (when-not id    (throw (ex-info "app spec missing :id" {:app app})))
  (when-not label (throw (ex-info "app spec missing :label" {:id id})))
  (when-not (and (vector? exec) (seq exec))
    (throw (ex-info "app spec missing :exec" {:id id})))
  app)


(defn- validate! [apps]
  (run! validate-app! apps)
  (let [ids (map :id apps)]
    (when (not= (count ids) (count (distinct ids)))
      (throw (ex-info "catalog has duplicate app :id" {:ids ids}))))
  apps)


(defn ->catalog
  "Index raw {:apps [...]} edn: creation order, by-id, and by-class (WM_CLASS -> id) — the
   lookup the agent uses to route an orphaned window to its workspace. :icon is whatever the
   loader resolved (an absolute path on device); no defaulting here."
  [raw]
  (let [apps (validate! (vec (:apps raw)))]
    {:order    (mapv :id apps)
     :by-id    (into {} (map (juxt :id identity)) apps)
     :by-class (into {} (keep (fn [a] (when (:class a) [(:class a) (:id a)]))) apps)}))


(defn listing
  "The launcher's projection: [{:id :label :icon :category}] in catalog order."
  [catalog]
  (mapv (fn [id]
          (let [a (get-in catalog [:by-id id])]
            {:id id :label (:label a) :icon (:icon a) :category (:category a)}))
        (:order catalog)))
