(ns ujima.desktop.app.catalog.loader
  "App dirs -> the catalog. Apps are external data: every spec is validated on read — shape by
   schema.ujima.app, then what it points at on disk — and a bad one is absent and logged."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [malli.core  :as m]
            [malli.error :as me]
            [lib.io    :as io]
            [ujima.log :as log]
            [schema.ujima.app :as defs]
            [ujima.desktop.app.catalog :as catalog]))


(defn- validate-shape!
  [spec]
  (when-not (m/validate defs/spec spec)
    (throw (ex-info (str "invalid app.edn: " (pr-str (me/humanize (m/explain defs/spec spec)))) {})))
  spec)


(defn- validate-files!
  "A web-app's :entry and a slash-relative argv[0] must exist under the app dir."
  [{:keys [kind exec entry dir] :as spec}]
  (case kind
    :exec    (let [argv0 (first exec)]
               (when (and (str/includes? argv0 "/")
                          (not (str/starts-with? argv0 "/"))
                          (not (fs/exists? (fs/path dir argv0))))
                 (throw (ex-info "relative argv[0] not in app dir" {:argv0 argv0}))))
    :web-app (when-not (fs/exists? (fs/path dir "app" entry))
               (throw (ex-info "web-app entry not found under app/" {:entry entry})))
    :link    nil)
  spec)


(defn- read-app
  "DIR/app.edn + what the scan resolves: :id = the dir name, :dir, :icon = the dir's icon.svg
   or FALLBACK-ICON, and for the web kinds :class ujima-<id>. Bad content logs and returns nil."
  [fallback-icon dir]
  (try
    (let [icon (fs/path dir "icon.svg")
          id   (keyword (fs/file-name dir))]
      (-> (io/slurp-edn (str (fs/path dir "app.edn")))
          (validate-shape!)
          (assoc :id id :dir (str dir)
                 :icon (if (fs/exists? icon) (str icon) fallback-icon))
          (as-> spec (if (#{:web-app :link} (:kind spec))
                       (assoc spec :class (str "ujima-" (name id)))
                       spec))
          (validate-files!)))
    (catch Throwable e
      (log/error "bad app.edn — app skipped" {:dir (str dir) :error (ex-message e)})
      nil)))


(defn- scan-root
  "The valid specs under ROOT in abc dir order; a missing root is a warning, not an error."
  [fallback-icon root]
  (if (and root (fs/directory? (str root)))
    (into [] (comp (filter fs/directory?)
                   (filter #(fs/exists? (fs/path % "app.edn")))
                   (keep (partial read-app fallback-icon)))
          (sort-by fs/file-name (fs/list-dir (str root))))
    (do (log/warn "app root missing — skipped" {:root (str root)}) [])))


(defn load-catalog
  "ROOTS scanned in order; a later root's app overrides an earlier one's, so storage can
   override baked. An empty catalog is an error line, not a crash."
  [roots fallback-icon]
  (let [apps (into [] (mapcat (partial scan-root fallback-icon)) roots)]
    (doseq [[id n] (frequencies (map :id apps)) :when (> n 1)]
      (log/info "app overridden by later root" {:app id}))
    (when (empty? apps)
      (log/error "app catalog is empty" {:roots (mapv str roots)}))
    (catalog/->catalog {:apps apps})))
