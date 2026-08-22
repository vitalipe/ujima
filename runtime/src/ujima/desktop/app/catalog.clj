(ns ujima.desktop.app.catalog
  "app.edn launch specs indexed by :id, validated loudly — and the one runtime write on them.")


(defonce ^:private catalog* (atom nil))


(defn validate-app!
  "Throw (ex-info, naming the offending field) unless APP has the catalog's identity core:
   a map with :id and :label. Kind-specific launchability is the loader's contract
   (ujima.desktop.app validate-kind!) — the catalog indexes identity, app->runnable computes
   the process."
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
  "Index raw {:apps [...]} edn: creation order, by-id, and by-class (WM_CLASS -> id) — the
   lookup ujimad uses to route an orphaned window to its workspace. :icon is whatever the
   loader resolved (an absolute path on device); no defaulting here."
  [raw]
  (let [apps (validate! (vec (:apps raw)))]
    {:order    (mapv :id apps)
     :by-id    (into {} (map (juxt :id identity)) apps)
     :by-class (into {} (keep (fn [a] (when (:class a) [(:class a) (:id a)]))) apps)}))


(defn listing
  "The shell's view of the catalog: [{:id :label :icon :category :hidden}] in catalog order."
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
  "Merge CHANGES (:env, :hidden) into ID's entry. Synchronous, so a launch that follows sees
   them; the changes, not a whole entry, so two writers can't clobber each other. A boot
   reloads the catalog."
  [id changes]
  (resolve! id)
  (swap! catalog* update-in [:by-id id] merge changes))
