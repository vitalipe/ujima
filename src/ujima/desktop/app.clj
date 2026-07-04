(ns ujima.desktop.app
  "The app domain's live edge (tmp/app-model/index.md): one catalog, one proc store.
   Validation/indexing is app.catalog, the fold is app.proc (both pure); the http
   face (/ui/apps) is desktop.http.app. The events listener thread is the store's
   single writer; readers get snapshots."
  (:require [babashka.fs :as fs]
            [lib.io      :as io]
            [ujima.desktop.app.catalog :as catalog]
            [ujima.desktop.app.proc    :as proc]))


(defonce ^:private catalog* (atom nil))
(defonce ^:private procs*   (atom (proc/init nil)))


(defn load-catalog!
  "Load + index apps.edn from PATH into the live catalog and seed the proc store.
   A missing or unreadable file throws — a broken image, not an empty desktop."
  [path]
  (when-not (and path (fs/exists? (str path)))
    (throw (ex-info "app catalog not found" {:path (str path)})))
  (let [raw (io/slurp-edn path)]
    (when-not (map? raw)
      (throw (ex-info "app catalog unreadable" {:path (str path)})))
    (let [cat (catalog/->catalog raw)]
      (reset! catalog* cat)
      (reset! procs* (proc/init cat))
      cat)))


(defn catalog-listing [] (catalog/listing @catalog*))
(defn handle-event!  [ev] (swap! procs* proc/apply-event ev))
(defn procs-snapshot []   (proc/snapshot @procs*))
