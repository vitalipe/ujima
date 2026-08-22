(ns ujima.desktop.app.catalog
  "App specs indexed by :id, and the one runtime write on them.")


(defonce ^:private catalog* (atom nil))


(defn validate-app!
  "The identity core: a map with :id and :label. Launchability is the loader's check."
  [{:keys [id label] :as app}]
  (when-not (map? app) (throw (ex-info "app spec is not a map" {:app app})))
  (when-not id    (throw (ex-info "app spec missing :id" {:app app})))
  (when-not label (throw (ex-info "app spec missing :label" {:id id})))
  app)


(defn- validate! [apps]
  (run! validate-app! apps)
  (let [ids (map :id apps)]
    (when (not= (count ids) (count (distinct ids)))
      (throw (ex-info "catalog has duplicate app :id" {:ids ids}))))
  apps)


(defn ->catalog
  "{:apps [...]} -> order, by-id, and by-class (WM_CLASS -> id, for routing an orphaned window)."
  [raw]
  (let [apps (validate! (vec (:apps raw)))]
    {:order    (mapv :id apps)
     :by-id    (into {} (map (juxt :id identity)) apps)
     :by-class (into {} (keep (fn [a] (when (:class a) [(:class a) (:id a)]))) apps)}))


(defn listing
  "[{:id :label :icon :category :hidden}] in catalog order."
  [catalog]
  (mapv (fn [id]
          (let [a (get-in catalog [:by-id id])]
            {:id id :label (:label a) :icon (:icon a) :category (:category a)
             :hidden (boolean (:hidden a))}))
        (:order catalog)))


(defn init! [catalog] (reset! catalog* catalog))

(defn current [] @catalog*)


(defn resolve!
  "ID's entry; an unknown app throws."
  [id]
  (or (get-in @catalog* [:by-id id])
      (throw (ex-info "unknown app" {:error :app/unknown-app :id id}))))


(defn merge-app!
  "Merge CHANGES (:env, :hidden) into ID's entry — synchronous, so a launch that follows sees
   them. A boot reloads the catalog."
  [id changes]
  (resolve! id)
  (swap! catalog* update-in [:by-id id] merge changes))
