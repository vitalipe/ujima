(ns ujima.desktop.catalog
  "The baked app catalog — the declarative list of apps the launcher can open. Loaded once from
   apps.edn and indexed by :id; the agent joins live windows back to catalog entries. Deliberately
   separate from ujima.control.defs: apps are a launch catalog, not reconciling settings."
  (:require [babashka.fs :as fs]
            [lib.io      :as io]))


(def kinds #{:shell :web :desktop})


(defn- validate!
  "Loud structural check — a bad baked catalog is a build error, not a runtime surprise."
  [apps]
  (doseq [{:keys [id kind url exec] :as app} apps]
    (when-not id           (throw (ex-info "catalog app missing :id" {:app app})))
    (when-not (kinds kind)  (throw (ex-info "catalog app has unknown :kind" {:id id :kind kind})))
    (when (and (= :web kind)     (not url))  (throw (ex-info "web app missing :url" {:id id})))
    (when (and (= :desktop kind) (not exec)) (throw (ex-info "desktop app missing :exec" {:id id}))))
  (let [ids (map :id apps)]
    (when (not= (count ids) (count (set ids)))
      (throw (ex-info "catalog has duplicate app :id" {:ids ids}))))
  apps)


(defn ->catalog
  "Index the raw `{:apps [...]}` edn into `{:apps [...] :by-id {id app}}`. Pure; validates loudly."
  [raw]
  (let [apps (validate! (vec (:apps raw)))]
    {:apps  apps
     :by-id (into {} (map (juxt :id identity)) apps)}))


(defn load!
  "Load + index the catalog from `path`. Missing file throws (a broken image, not an empty desktop)."
  [path]
  (when-not (fs/exists? path)
    (throw (ex-info "app catalog not found" {:path (str path)})))
  (->catalog (io/slurp-edn path)))


(defn apps [catalog]    (:apps catalog))
(defn app  [catalog id] (get-in catalog [:by-id id]))
