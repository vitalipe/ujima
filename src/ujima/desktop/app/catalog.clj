(ns ujima.desktop.app.catalog
  "app.edn launch specs indexed by :id, validated loudly. Pure.")


(defn- validate! [apps]
  (doseq [{:keys [id label exec] :as app} apps]
    (when-not id    (throw (ex-info "catalog app missing :id" {:app app})))
    (when-not label (throw (ex-info "catalog app missing :label" {:id id})))
    (when-not (and (vector? exec) (seq exec))
      (throw (ex-info "catalog app missing :exec" {:id id}))))
  (let [ids (map :id apps)]
    (when (not= (count ids) (count (distinct ids)))
      (throw (ex-info "catalog has duplicate app :id" {:ids ids}))))
  apps)


(defn ->catalog
  "Index raw {:apps [...]} edn: creation order, by-id (:icon defaulted to the id), and by-class
   (WM_CLASS -> id) — the lookup the agent uses to route an orphaned window to its workspace."
  [raw]
  (let [apps (mapv (fn [a] (update a :icon #(or % (name (:id a)))))
                   (validate! (vec (:apps raw))))]
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
