(ns ujima.desktop.app.catalog
  "App specs indexed by :id, and the one runtime write on them."
  (:require [lib.util :refer [distinct-by index-by map-vals]]))


(defonce ^:private catalog* (atom nil))


(defn ->catalog
  "{:apps [...]} -> order (abc on id), by-id (a repeated :id keeps its last entry), and
   by-class (WM_CLASS -> id, for routing an orphaned window)."
  [raw]
  (let [apps (distinct-by :id (reverse (:apps raw)))]
    {:order    (mapv :id (sort-by :id apps))
     :by-id    (index-by :id apps)
     :by-class (->> apps 
                 (filter :class) 
                 (index-by :class) (map-vals :id))}))


(defn listing
  "[{:id :label :icon :category :hidden}] in catalog order."
  [catalog]
  (mapv (fn [id]
          (let [a (get-in catalog [:by-id id])]
            {:id id :label (:label a) :icon (:icon a) :category (:category a)
             :hidden (boolean (:hidden a))}))
        (:order catalog)))


(defn init! [catalog] 
  (reset! catalog* catalog))


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
