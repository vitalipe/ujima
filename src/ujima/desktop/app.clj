(ns ujima.desktop.app
  "The app domain's live edge (tmp/app-model/index.md): one catalog, one proc store.
   Validation/indexing is app.catalog, the fold is app.proc (both pure); the http face
   (/ui/apps) is desktop.http.app. start-app! is the dumb executor — it runs any app
   map's :exec verbatim and never consults the catalog; run-from-catalog! is the id ->
   map sugar the launcher uses. Readers get snapshots."
  (:require [babashka.fs      :as fs]
            [babashka.process :as p]
            [lib.io    :as io]
            [lib.shell :as shell]
            [ujima.log :as log]
            [ujima.desktop.app.catalog :as catalog]
            [ujima.desktop.app.proc    :as proc]))


(defonce ^:private catalog* (atom nil))
(defonce ^:private procs*   (atom (proc/init nil)))
(defonce ^:private handles* (atom {}))   ; app-id -> live process handle: the impure twin of
                                         ; the store's :pid (kill/deref need the object; the
                                         ; pure store stays printable/comparable data)


(defn load-catalog!
  "Load + index apps.edn from PATH into the live catalog, and seed the proc store's
   adoption index with its classes. A missing or unreadable file throws — a broken
   image, not an empty desktop."
  [path]
  (when-not (and path (fs/exists? (str path)))
    (throw (ex-info "app catalog not found" {:path (str path)})))
  (let [raw (io/slurp-edn path)]
    (when-not (map? raw)
      (throw (ex-info "app catalog unreadable" {:path (str path)})))
    (let [cat (catalog/->catalog raw)]
      (reset! catalog* cat)
      (reset! procs* (proc/init (:class->app cat)))
      cat)))


(defn catalog-listing [] (catalog/listing @catalog*))
(defn handle-event!  [ev] (swap! procs* proc/apply-event ev))
(defn procs-snapshot []   (proc/snapshot @procs*))



(defn app-running? [{:keys [id] :as app}]
  (not (nil? (get-in @procs* [:procs id]))))


(defn require-valid-app! [{:keys [id exec class] :as app}]
  (when (nil? app)
    (throw (ex-info "unknown app" {:error :app/unknown-app})))

  (when-not (and id (vector? exec) (seq exec) (string? class))
    (throw (ex-info "app map needs :id, :exec and :class" {:error :app/invalid-app :app app})))

  true)


(defn- spawn! [{:keys [id exec] :as app}]
  (let [proc (apply shell/sh {:out :inherit :err :inherit :shutdown p/destroy-tree} exec)
        pid  (try (.pid (:proc proc)) (catch Throwable _ nil))]

      (swap! handles* assoc id proc)

      (handle-event! {:type :proc/started :app app :pid pid})
      (log/info "app spawned" {:app id :pid pid})))


(defn run! [{:keys [id exec class] :as app}]
  (require-valid-app! app)

  (if (app-running? app)
    (log/info "app already open" {:app id})
    (spawn! app)))


(defn run-from-catalog!
  "POST /app/run's verb: resolve ID in the catalog and hand the map to the executor."
  [id]
  (let [app (get-in @catalog* [:by-id id])]
    (when-not app
      (throw (ex-info "unknown app" {:error :app/unknown-app :id id})))
    (run! app)))
