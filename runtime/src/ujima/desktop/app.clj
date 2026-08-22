(ns ujima.desktop.app
  "The app layer: the catalog scan, the listener loop — observe, act, re-observe, project — and
   the verbs that feed it. The WORKSPACE is an app's identity (app :write lives on workspace
   \"write\", home is \"1\"), so windows are never matched on WM_CLASS. Each launch lands in a
   systemd --user scope: alive? and kill. i3 owns placement and focus. Verbs, i3 events and
   scope deaths ride one listener thread (i3/emit!)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [malli.core  :as m]
            [malli.error :as me]
            [lib.io    :as io]
            [ujima.log :as log]
            [schema.ujima.app :as defs]
            [ujima.linux.i3 :as i3]
            [ujima.desktop.app.catalog    :as catalog]
            [ujima.desktop.app.act        :as act]
            [ujima.desktop.app.projection :as proj :refer [home-ws]]))


(defonce ^:private prev*    (atom nil))   ; last snapshot, the prv of (next prv)
(defonce ^:private targets* (atom []))


(def ^:private browser-app :web)


(defn- validate-shape!
  "Throw, naming the fields, unless SPEC is an app.edn per schema.ujima.app."
  [spec]
  (when-not (m/validate defs/spec spec)
    (throw (ex-info (str "invalid app.edn: " (pr-str (me/humanize (m/explain defs/spec spec)))) {})))
  spec)


(defn- validate-files!
  "Throw unless what SPEC points at exists under its dir: a web-app's :entry, and a
   slash-relative argv[0] (bare commands are PATH lookups, absolute paths are trusted).
   Packaging errors fail at scan: a broken app is absent and logged."
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
  "DIR/app.edn -> spec + what scanning resolves: :id = the dir name, :dir = the dir (spawn cwd),
   :icon = the dir's icon.svg or FALLBACK-ICON, and for the web kinds a derived :class
   ujima-<id>. Bad content logs and returns nil — an app can break itself, never the session."
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
          (validate-files!)
          (catalog/validate-app!)))
    (catch Throwable e
      (log/error "bad app.edn — app skipped" {:dir (str dir) :error (ex-message e)})
      nil)))


(defn- scan-root
  "All valid app specs under ROOT, in abc dir order: each subdir holding an app.edn is an app.
   A missing root contributes nothing — a warning, a fresh storage partition is normal."
  [fallback-icon root]
  (if (and root (fs/directory? (str root)))
    (into [] (comp (filter fs/directory?)
                   (filter #(fs/exists? (fs/path % "app.edn")))
                   (keep (partial read-app fallback-icon)))
          (sort-by fs/file-name (fs/list-dir (str root))))
    (do (log/warn "app root missing — skipped" {:root (str root)}) [])))


(defn load-catalog
  "The catalog from ROOTS, scanned in order: specs merge by :id, later root wins, so a storage
   app can override a baked one; the final order is abc on id. Bad entries are skipped loudly,
   and the session boots regardless — an empty catalog is an error line, not a crash."
  [roots fallback-icon]
  (let [merged (reduce (fn [m {:keys [id] :as app}]
                         (when (contains? m id)
                           (log/info "app overridden by later root" {:app id}))
                         (assoc m id app))
                       {}
                       (mapcat (partial scan-root fallback-icon) roots))
        apps   (vec (sort-by (comp name :id) (vals merged)))]
    (when (empty? apps)
      (log/error "app catalog is empty" {:roots (mapv str roots)}))
    (catalog/->catalog {:apps apps})))


(defn init! [{:keys [catalog converge-targets] :as cfg}]
  (catalog/init! catalog)
  (reset! prev*    nil)
  (reset! targets* (vec converge-targets))
  (act/init! (select-keys cfg [:open-web-app-bin :serve-web-app-bin]))
  catalog)


(defn catalog-listing [] (catalog/listing (catalog/current)))

(defn icon-path
  "The catalog-resolved icon path for ID; nil for an unknown app."
  [id]
  (get-in (catalog/current) [:by-id id :icon]))


;; --- the loop: observe, act, re-observe, project ---

(defn- observe! []
  {:focused-ws (i3/focused-workspace)
   :ws->wins   (group-by :workspace (i3/window-facts (i3/get-tree!)))
   :catalog    (catalog/current)})


(defn- converge! [snapshot]
  ;; swap-vals! so two threads can't claim the same prev; ORDER still needs the one listener
  (let [prv (first (swap-vals! prev* (constantly snapshot)))]
    (doseq [t @targets*] (t snapshot prv))))


(defn handle-event! [ev]
  (case (:type ev)
    :app/run       (act/run! (:app ev) (:extra ev []))
    :app/switch    (i3/switch-workspace! (name (:id (:app ev))))
    :app/open-url  (act/open-url! (:app ev) (:url ev))
    :app/close     (act/close! (observe!))
    :app/home      (i3/switch-workspace! home-ws)
    :app/cycle     (act/cycle! (observe!) (:step ev))
    :window/closed (act/window-closed! (observe!) (:con-id ev))
    :scope/died    (act/go-home-if-empty! (observe!) (:app-id ev))   ; crash / self-quit
    nil)

  (let [w (observe!)]
    (act/settle-floaters! w)
    (act/route-windows! w)
    (-> w proj/projection converge!)))


;; --- verbs: validate, then ride the pipe ---

(defn run!           [id] (i3/emit! {:type :app/run    :app (catalog/resolve! id)}))
(defn switch-to!     [id] (i3/emit! {:type :app/switch :app (catalog/resolve! id)}))
(defn close-focused! []   (i3/emit! {:type :app/close}))
(defn go-home!       []   (i3/emit! {:type :app/home}))
(defn cycle!         [step] (i3/emit! {:type :app/cycle :step step}))

(defn open-url! [url]
  (when-not (re-matches #"https?://\S+" (str url))
    (throw (ex-info "not an http url" {:error :app/bad-url :url (str url)})))
  (i3/emit! {:type :app/open-url :app (catalog/resolve! browser-app) :url url}))

(defn current-apps-state [] (or @prev* {:running [] :catalog [] :current nil}))
