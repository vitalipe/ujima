(ns ujima.desktop.app.catalog
  "The app catalog — apps.edn launch specs ({:apps [spec …]}, tmp/app-model/index.md)
   validated loudly and indexed by :id. Pure; loading and the live atom are
   ujima.desktop.app's."
  (:require [clojure.string :as str]))


(defn window-class
  "The WM_CLASS this app's windows are adopted by: a declared natural :class (natives),
   else the stamped ujima-<id>."
  [app]
  (or (:class app) (str "ujima-" (name (:id app)))))


(defn- validate!
  "Loud structural check — a bad baked catalog is a build error, not a runtime surprise."
  [apps]
  (doseq [{:keys [id label exec] :as app} apps]
    (when-not id    (throw (ex-info "catalog app missing :id" {:app app})))
    (when-not label (throw (ex-info "catalog app missing :label" {:id id})))
    (when-not (and (vector? exec) (seq exec))
      (throw (ex-info "catalog app missing :exec" {:id id})))
    (when (and (:class app) (:class-flag app))
      (throw (ex-info "catalog app declares both :class and :class-flag" {:id id}))))
  (let [ids (map :id apps)]
    (when (not= (count ids) (count (distinct ids)))
      (throw (ex-info "catalog has duplicate app :id" {:ids ids}))))
  (let [classes (map (comp str/lower-case window-class) apps)]
    (when (not= (count classes) (count (distinct classes)))
      (throw (ex-info "catalog apps share a WM_CLASS" {:classes classes}))))
  apps)


(defn ->catalog
  "Index raw {:apps [...]} edn: creation order, by-id, and the lower-cased WM_CLASS ->
   app-id adoption index (casing varies by app — i3 reports TuxPaint, Pcmanfm, …).
   Validates loudly."
  [raw]
  (let [apps (validate! (vec (:apps raw)))]
    {:order     (mapv :id apps)
     :by-id     (into {} (map (juxt :id identity)) apps)
     :class->id (into {} (map (fn [a] [(str/lower-case (window-class a)) (:id a)])) apps)}))


(defn listing
  "The GET /app/catalog projection: [{:id :label :icon}] in catalog order."
  [catalog]
  (mapv (fn [id]
          (let [a (get-in catalog [:by-id id])]
            {:id id :label (:label a) :icon (or (:icon a) (name id))}))
        (:order catalog)))
