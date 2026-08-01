(ns ujima.desktop.app
  "The app layer: a small write side (run / switch / close / home / cycle) and a projection.
   The WORKSPACE is an app's identity — app :write lives on workspace \"write\", home is \"1\" —
   so we never match on WM_CLASS. Each launch lands in a systemd --user scope
   (ujima.linux.systemd), which answers 'is this app alive?' (the launch gate + the go-home
   backstop) and 'kill it' (force-close). i3 owns placement/focus; the tree owns display.
   Verbs + i3 events + scope-death events all ride one listener thread (i3/emit!)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [lib.io    :as io]
            [lib.shell :as shell]
            [ujima.log :as log]
            [ujima.linux.i3      :as i3]
            [ujima.linux.systemd :as systemd]
            [ujima.desktop.app.catalog :as catalog]))


(defonce ^:private catalog* (atom nil))
(defonce ^:private prev*    (atom nil))   ; last snapshot, the prv of (next prv)
(defonce ^:private targets* (atom []))
(defonce ^:private close*   (atom nil))   ; last ✕: {:con :app :at} — drives con-id go-home + ✕✕


(def ^:private home-ws     "1")
(def ^:private browser-app :web)
(def force-lo-ms 1000)                     ; a 2nd ✕ sooner = accidental double-click (ignore)
(def force-hi-ms 3000)                     ; a 2nd ✕ later = a fresh close, not an escalation


;; the generic app face: an app dir without an icon.svg renders this instead of breaking the
;; front-ends — icon resolution is a loader concern, so the catalog always carries a real path
(def ^:private fallback-icon "/ujima/desktop/icons/launcher.svg")

;; the desugar targets: :link / :web-app specs become invocations of these at spawn time
(def ^:private open-web-app-bin  "/ujima/desktop/bin/ujima-open-web-app")
(def ^:private serve-web-app-bin "/ujima/desktop/bin/ujima-serve-web-app")


(defn- validate-kind!
  "Throw unless SPEC is launchable for its :kind (default :exec) — the loader's half of app
   validity (catalog/validate-app! keeps the identity core; app->runnable trusts what passes
   here). Packaging errors fail HERE, at scan: a broken app is absent-and-logged, never a
   tile that is dead on click. Unknown :kind throws too — a future kind degrades to absent
   on an OS that doesn't know it."
  [{:keys [kind exec entry port url dir] :as spec}]
  (case (or kind :exec)
    :exec    (do (when-not (and (vector? exec) (seq exec))
                   (throw (ex-info "app spec missing :exec" {})))
                 (let [argv0 (first exec)]
                   ;; slash-relative argv[0] resolves against the app dir (spawn cwd) — verify
                   ;; it exists; bare commands are PATH lookups and absolute paths are trusted
                   (when (and (string? argv0)
                              (str/includes? argv0 "/")
                              (not (str/starts-with? argv0 "/"))
                              (not (fs/exists? (fs/path dir argv0))))
                     (throw (ex-info "relative argv[0] not in app dir" {:argv0 argv0})))))
    :web-app (do (when-not (and entry port)
                   (throw (ex-info "web-app needs :entry and :port" {})))
                 (when-not (fs/exists? (fs/path dir "app" (str entry)))
                   (throw (ex-info "web-app entry not found under app/" {:entry entry}))))
    :link    (when-not url (throw (ex-info "link missing :url" {})))
    (throw (ex-info "unknown app :kind" {:kind kind})))
  spec)


(defn- read-app
  "One scan entry: DIR/app.edn -> spec + what scanning resolves: :id = the dir name, :dir =
   the dir itself (spawn cwd; relative paths in the spec resolve there), :icon = the dir's
   icon.svg (fallback glyph when absent), and for the web kinds a DERIVED :class ujima-<id>
   (authored :class ignored — the kind owns window identity). Bad content logs and returns
   nil — an app can break itself, never the session."
  [dir]
  (try
    (let [icon (fs/path dir "icon.svg")
          id   (keyword (fs/file-name dir))]
      (-> (io/slurp-edn (str (fs/path dir "app.edn")))
          (assoc :id id :dir (str dir)
                 :icon (if (fs/exists? icon) (str icon) fallback-icon))
          (as-> spec (if (#{:web-app :link} (:kind spec))
                       (assoc spec :class (str "ujima-" (name id)))
                       spec))
          (validate-kind!)
          (catalog/validate-app!)))
    (catch Throwable e
      (log/error "bad app.edn — app skipped" {:dir (str dir) :error (ex-message e)})
      nil)))


(defn- scan-root
  "All valid app specs under ROOT, in abc dir order: each subdir directly containing an
   app.edn is an app (payload-only dirs are invisible). A missing root contributes nothing —
   warn, not error: a fresh storage partition is a normal state."
  [root]
  (if (and root (fs/directory? (str root)))
    (into [] (comp (filter fs/directory?)
                   (filter #(fs/exists? (fs/path % "app.edn")))
                   (keep read-app))
          (sort-by fs/file-name (fs/list-dir (str root))))
    (do (log/warn "app root missing — skipped" {:root (str root)}) [])))


(defn load-catalog
  "Build the catalog from ROOTS (a vector, scanned in index order): specs merge by :id —
   later root wins, so a storage app can override a baked one; the final order is abc on id
   (an override keeps its tile position). Apps are external data to the OS: bad entries are
   skipped loudly, a missing root contributes nothing, and the session boots regardless —
   an empty catalog is an error line, not a crash."
  [roots]
  (let [merged (reduce (fn [m {:keys [id] :as app}]
                         (when (contains? m id)
                           (log/info "app overridden by later root" {:app id}))
                         (assoc m id app))
                       {}
                       (mapcat scan-root roots))
        apps   (vec (sort-by (comp name :id) (vals merged)))]
    (when (empty? apps)
      (log/error "app catalog is empty" {:roots (mapv str roots)}))
    (catalog/->catalog {:apps apps})))


(defn init! [{:keys [catalog converge-targets]}]
  (reset! catalog* catalog)
  (reset! prev*    nil)
  (reset! close*   nil)
  (reset! targets* (vec converge-targets))
  catalog)


(defn catalog-listing [] (catalog/listing @catalog*))

(defn icon-path
  "The catalog-resolved icon path for ID (nil for an unknown app) — the /app/icon/<id> route
   serves this file, so the webview launcher never touches the filesystem layout."
  [id]
  (get-in @catalog* [:by-id id :icon]))


;; --- observe + projection (read side; the tree = display) ---

(defn- observe! []
  {:focused-ws (i3/focused-workspace)
   :ws->wins   (group-by :workspace (i3/window-facts (i3/get-tree!)))})


(defn- app-of-ws [ws]
  (when (and ws (not= ws home-ws))
    (first (filter #(= (name %) ws) (:order @catalog*)))))


(defn- open-apps
  "App ids with windows on their workspace, in catalog (= dock) order — the one definition
   of 'open', shared by the projection (dock/launcher) and the Alt+Tab ring."
  [ws->wins]
  (filter #(seq (get ws->wins (name %))) (:order @catalog*)))


(defn- entry [id ws->wins]
  (let [a    (get-in @catalog* [:by-id id])
        wins (get ws->wins (name id))]
    {:id id :label (:label a) :icon (:icon a) :category (:category a)
     :title (:title (or (first (filter :focused? wins)) (first wins)))
     ;; detected (the window is really fullscreen) OR declared (:mode) — declared is the escape
     ;; hatch for an app that draws full-screen without setting the fullscreen state
     :fullscreen (boolean (or (some :fullscreen? wins) (= :fullscreen (:mode a))))}))


(defn- projection [{:keys [focused-ws ws->wins]}]
  (let [open (mapv #(entry % ws->wins) (open-apps ws->wins))]
    {:apps    open
     :current (when-let [id (app-of-ws focused-ws)] (entry id ws->wins))}))


(defn- converge! [snapshot]
  (let [prv @prev*]
    (reset! prev* snapshot)
    (doseq [t @targets*] (t snapshot prv))))


;; --- act (write side; listener thread only). scope = lifecycle ---

(defn app->runnable
  "SPEC -> argv, computed at spawn time — the one seam where a kind becomes a process
   (bwrap wrapping, per-user paths etc. hook here later). :exec runs as-authored (cwd = the
   app dir, so relative paths resolve there; bare commands stay PATH lookups); :web-app
   serves <dir>/app on :port and opens it kiosk; :link opens :url kiosk. Trusts scan-time
   validate-kind!."
  [{:keys [kind exec dir entry port url class]}]
  (case (or kind :exec)
    :exec    (vec exec)
    :web-app [serve-web-app-bin (str (fs/path dir "app")) (str entry) (str port) class]
    :link    [open-web-app-bin (str url) class]))


(defn- do-run!
  "Switch to the app's workspace, then launch into a scope only if it isn't already up. The
   scope is the gate: an app still cold-starting counts as up, so a re-tap never double-spawns."
  [{:keys [id dir] :as app} extra]
  (i3/switch-workspace! (name id))
  (when-not (systemd/active? id)
    (try
      (systemd/spawn-scoped! id (into (app->runnable app) extra) dir)
      (log/info "app launched" {:app id})
      (catch Throwable e
        (log/error "app launch failed" {:app id :error (ex-message e)})
        (i3/switch-workspace! home-ws)))))


(defn- do-open-url!
  "A link -> the Web app. Warm: a plain messenger joins the running instance (its window lands
   in the existing scope). Cold: a normal scoped launch with the url. Either way, switch there."
  [{:keys [id dir] :as app} url]
  (if (systemd/active? id)
    (do (apply shell/sh {:out :inherit :err :inherit :dir dir} (conj (app->runnable app) url))
        (i3/switch-workspace! (name id)))
    (do-run! app [url])))


(defn- do-close!
  "First ✕ = polite WM_DELETE (the app owns save-prompts etc.), arming a record. A deliberate 2nd
   ✕ on the SAME still-focused window in the 1-3s window = force-kill the scope. No focused window
   on an app workspace (zombie / launch to abort) = force-kill too."
  [{:keys [focused-ws ws->wins]}]
  (when-let [app (app-of-ws focused-ws)]
    (let [con (:con-id (first (filter :focused? (get ws->wins focused-ws))))
          now (System/currentTimeMillis)
          rec @close*]
      (cond
        (nil? con)
        (do (systemd/stop! app) (reset! close* nil))

        (and rec (= app (:app rec)) (= con (:con rec))
             (<= force-lo-ms (- now (:at rec)) force-hi-ms))
        (do (log/warn "force-close" {:app app})
            (systemd/stop! app) (reset! close* nil))

        :else
        (do (i3/kill-focused!)
            (reset! close* {:con con :app app :at now}))))))


(defn- do-cycle!
  "Alt+Tab: hop the ring of RUNNING apps in catalog order, so the cycle matches the dock
   left-to-right. HOME is deliberately NOT a stop (HW: passing through the launcher felt
   weird) — from outside the ring (home, an app ws emptying mid-close) enter at the first
   app going forward / the last going backward. No apps running = no-op."
  [{:keys [focused-ws ws->wins]} step]
  (let [ring (mapv name (open-apps ws->wins))
        idx  (.indexOf ring focused-ws)]
    (when (seq ring)
      (let [target (if (neg? idx)
                     (if (pos? step) (first ring) (peek ring))
                     (nth ring (mod (+ idx step) (count ring))))]
        (when (not= target focused-ws)
          (i3/switch-workspace! target))))))


(defn- go-home-if-empty!
  "Go home only if we're looking at APP-ID's now-empty workspace — idempotent across the con-id
   and scope-death triggers, and never fires for a background app dying elsewhere."
  [app-id {:keys [focused-ws ws->wins]}]
  (when (and (= focused-ws (name app-id)) (empty? (get ws->wins focused-ws)))
    (log/info "empty app workspace — going home" {:ws focused-ws})
    (i3/switch-workspace! home-ws)))


(defn- settle-floaters!
  "chromium --app auto-floats (fixed size hints) and sets class/role only AFTER mapping, so i3's
   for_window can't catch it. Un-float floating non-dialog windows on app workspaces. Idempotent."
  [{:keys [ws->wins]}]
  (doseq [[ws wins] ws->wins
          {:keys [con-id floating? wtype]} wins
          :when (and (app-of-ws ws) floating?
                     (not (#{"dialog" "utility" "splash"} wtype)))]
    (i3/command? (format "[con_id=%d]" con-id) "floating" "disable")))


(defn- route-windows!
  "The orphan backstop: a window that mapped on the wrong workspace (focus moved away during the
   launch) is moved to its app's workspace, matched by WM_CLASS — INCLUDING the app's own dialogs
   (e.g. Inkscape's startup dialog, which maps before its main window), which must land with their
   app, not strand on home. Only class-matching windows move, so non-app dialogs are untouched.
   No focus change, so it goes to its place while the kid stays put; idempotent (home = no-op)."
  [{:keys [ws->wins]}]
  (let [by-class (:by-class @catalog*)]
    (doseq [[ws wins] ws->wins
            {:keys [con-id class]} wins
            :let  [target (get by-class class)]
            :when (and target (not= ws (name target)))]
      (i3/command? (format "[con_id=%d]" con-id) "move" "container" "to" "workspace" (name target)))))


(defn handle-event! [ev]
  (case (:type ev)
    :app/run      (do-run! (:app ev) (:extra ev []))
    :app/switch   (i3/switch-workspace! (name (:id (:app ev))))
    :app/open-url (do-open-url! (:app ev) (:url ev))
    :app/close    (do-close! (observe!))
    :app/home     (i3/switch-workspace! home-ws)
    :app/cycle    (do-cycle! (observe!) (:step ev))
    :window/close (when-let [rec @close*]                       ; the window the user ✕'d closed
                    (when (= (:con-id ev) (:con rec))
                      (reset! close* nil)
                      (let [app (:app rec) w (observe!)]
                        ;; its workspace is now empty -> the user's close took: make sure the app is
                        ;; really gone (a still-launching process would else re-map a stray window)
                        (when (empty? (get (:ws->wins w) (name app)))
                          (systemd/stop! app)
                          (go-home-if-empty! app w)))))
    :scope/died   (go-home-if-empty! (:app-id ev) (observe!))   ; crash / self-quit backstop
    nil)

  (let [w (observe!)]
    (settle-floaters! w)
    (route-windows! w)
    (-> w projection converge!)))


;; --- verbs: validate, then ride the pipe ---

(defn- resolve! [id]
  (or (get-in @catalog* [:by-id id])
      (throw (ex-info "unknown app" {:error :app/unknown-app :id id}))))

(defn run!           [id] (i3/emit! {:type :app/run    :app (resolve! id)}))
(defn switch-to!     [id] (i3/emit! {:type :app/switch :app (resolve! id)}))
(defn close-focused! []   (i3/emit! {:type :app/close}))
(defn go-home!       []   (i3/emit! {:type :app/home}))
(defn cycle!         [step] (i3/emit! {:type :app/cycle :step step}))

(defn open-url! [url]
  (when-not (re-matches #"https?://\S+" (str url))
    (throw (ex-info "not an http url" {:error :app/bad-url :url (str url)})))
  (i3/emit! {:type :app/open-url :app (resolve! browser-app) :url url}))

(defn current-apps-state [] (or @prev* {:apps [] :current nil}))
