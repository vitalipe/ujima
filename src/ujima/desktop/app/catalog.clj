(ns ujima.desktop.app.catalog
  "The app catalog — apps.edn launch specs ({:apps [spec …]}, tmp/app-model/index.md)
   validated loudly and indexed by :id. Pure; loading and the live atom are
   ujima.desktop.app's."
  (:require [clojure.string :as str]))


(defn- validate!
  "Loud structural check — a bad baked catalog is a build error, not a runtime surprise.
   :class is required: it is the adoption key (for chromium apps it must match the
   --class= inside :exec — drift means the app never leaves :starting)."
  [apps]
  (doseq [{:keys [id label exec class] :as app} apps]
    (when-not id    (throw (ex-info "catalog app missing :id" {:app app})))
    (when-not label (throw (ex-info "catalog app missing :label" {:id id})))
    (when-not (and (vector? exec) (seq exec))
      (throw (ex-info "catalog app missing :exec" {:id id})))
    (when-not (string? class)
      (throw (ex-info "catalog app missing :class" {:id id}))))
  (let [ids (map :id apps)]
    (when (not= (count ids) (count (distinct ids)))
      (throw (ex-info "catalog has duplicate app :id" {:ids ids}))))
  (let [classes (map (comp str/lower-case :class) apps)]
    (when (not= (count classes) (count (distinct classes)))
      (throw (ex-info "catalog apps share a WM_CLASS" {:classes classes}))))
  apps)


(defn ->catalog
  "Index raw {:apps [...]} edn: creation order, by-id (:icon defaulted to the id),
   and the lower-cased WM_CLASS -> app-map adoption seed (casing varies by app —
   i3 reports TuxPaint, Pcmanfm, …). Validates loudly."
  [raw]
  (let [apps (mapv (fn [a] (update a :icon #(or % (name (:id a)))))
                   (validate! (vec (:apps raw))))]
    {:order      (mapv :id apps)
     :by-id      (into {} (map (juxt :id identity)) apps)
     :class->app (into {} (map (fn [a] [(str/lower-case (:class a)) a])) apps)}))


(defn listing
  "The launcher's projection: [{:id :label :icon}] in catalog order."
  [catalog]
  (mapv (fn [id]
          (let [a (get-in catalog [:by-id id])]
            {:id id :label (:label a) :icon (:icon a)}))
        (:order catalog)))
