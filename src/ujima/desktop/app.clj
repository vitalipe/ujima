(ns ujima.desktop.app
  "The app domain's edge — an actor over the single window stream. Three planes,
   each owning only its own state: windows (close-intents), procs (the spawn
   registry), lifecycle (nothing — a pure join). Window events, :recheck/* echoes
   and the commands the verbs emit! all ride linux.i3's stream into handle-event!."
  (:require [babashka.fs      :as fs]
            [babashka.process :as p]
            [lib.io    :as io]
            [lib.shell :as shell]
            [ujima.log :as log]
            [ujima.linux.i3              :as i3]
            [ujima.desktop.app.catalog   :as catalog]
            [ujima.desktop.app.windows   :as windows]
            [ujima.desktop.app.procs     :as procs]
            [ujima.desktop.app.lifecycle :as lc]))


(defonce ^:private catalog*  (atom nil))
(defonce ^:private wintents* (atom {}))  ; the window plane's state: con-id -> asked-at
(defonce ^:private procs*    (atom {}))  ; the proc plane's state: the spawn registry
(defonce ^:private push*     (atom nil)) ; the GUI edge, wired by core (set-push!)


(def ^:private staging-workspace "ujima-loading")  ; spawn maps here, not splitting the launcher
(def ^:private home-workspace    "1")              ; where eww's launcher window lives


(defn load-catalog!
  "Load + index apps.edn. Missing or unreadable throws — a broken image, not an
   empty desktop."
  [path]
  (when-not (and path (fs/exists? (str path)))
    (throw (ex-info "app catalog not found" {:path (str path)})))
  (let [raw (io/slurp-edn path)]
    (when-not (map? raw)
      (throw (ex-info "app catalog unreadable" {:path (str path)})))
    (let [cat (catalog/->catalog raw)]
      (reset! catalog* cat)
      (reset! wintents* {})
      (reset! procs* {})
      cat)))


(defn set-push!
  "Install the GUI edge (wired by core)."
  [f]
  (reset! push* f))


(defn catalog-listing [] (catalog/listing @catalog*))


(defn- look!
  "Ingest reality: read the tree, settle our ledgers against it (answered
   close-intents pruned, windowed spawns marked), derive. Touches nothing real —
   asking for a view cannot move a window."
  []
  (let [ws       (windows/from-tree (i3/get-tree!))
        _        (swap! wintents* windows/resolve-intents ws)
        registry (swap! procs* procs/mark-windowed (windows/apps-present @catalog* ws))]
    {:ws ws :view (lc/view @catalog* ws registry)}))


(defn- publish! [view]
  (when-let [push! @push*]
    (push! (lc/snapshot view))))


;; --- the act phase (listener thread only; each returns truthy iff it changed the world) ---

(defn- enforce-placement!
  "Move strays per the plan."
  [ws]
  (let [plan (windows/to-place @catalog* ws home-workspace)]
    (doseq [{:keys [con-id workspace]} plan]
      (i3/place! con-id workspace))
    (boolean (seq plan))))


(defn- rescue-stranded!
  "Nothing of ours focused on a dead app workspace (i3 never leaves one) -> home.
   Staging is the loading wait; the proc recheck owns it."
  [view]
  (when (nil? (:current view))
    (let [fws (i3/focused-workspace)]
      (when-not (#{home-workspace staging-workspace} fws)
        (i3/switch-workspace! home-workspace)
        true))))

(defn- do-run!
  "Gate on the derived SM: :running -> focus (ensure-open), :new -> no-op,
   :closed -> spawn onto staging; placement hands it to its own workspace."
  [view {:keys [id exec] :as app}]
  (case (lc/state-of view id)
    :running (do (log/info "app already open — focusing" {:app id})
                 (i3/switch-workspace! (name id))
                 true)     ; moved focus = acted
    :new     (do (log/info "run gated — still opening" {:app id})
                 false)
    ;; :shutdown — no orphans if the agent dies; :inherit — an unread pipe would fill
    (let [_    (i3/switch-workspace! staging-workspace)
          proc (apply shell/sh {:out :inherit :err :inherit :shutdown p/destroy-tree} exec)
          pid  (try (.pid (:proc proc)) (catch Throwable _ nil))
          at   (System/currentTimeMillis)]
      (swap! procs* assoc id {:handle proc :pid pid :spawned-at at})
      (i3/hint-proc! id at)
      (log/info "app spawned" {:app id :pid pid})
      true)))


(defn- do-close-focused!
  "WM_close the FOCUSED window — a window verb: one LibreOffice doc closes, the
   app stays :running. A pending intent gates a re-close."
  [view]
  (let [{:keys [con-id]} (:focused view)]
    (cond
      (or (nil? con-id) (nil? (:current view)))
      (do (log/info "close gated — no managed window focused" {}) false)

      (get @wintents* con-id)
      (do (log/info "close already pending" {:con con-id}) false)

      :else
      (let [at (System/currentTimeMillis)]
        (swap! wintents* assoc con-id at)
        (i3/kill-con! con-id)
        (i3/hint-window! con-id at)
        (log/info "close sent" {:con con-id})
        true))))


(defn- expire-proc!
  "The spawn never windowed (the look just re-read the tree): kill the leftover,
   drop the entry, rescue the user from staging."
  [{:keys [app-id at]}]
  (let [{:keys [handle spawned-at windowed?]} (get @procs* app-id)]
    (when (and handle (= at spawned-at) (not windowed?))
      (log/warn "spawn never windowed — killing it" {:app app-id})
      (try (p/destroy-tree handle) (catch Throwable _))
      (swap! procs* dissoc app-id)
      (when (= staging-workspace (i3/focused-workspace))
        (i3/switch-workspace! home-workspace))
      true)))


(defn- expire-window-intent!
  "The close went unanswered — a quit-confirm holds the window. Drop the intent."
  [{:keys [con-id at]}]
  (when (= at (get @wintents* con-id))
    (log/warn "close unanswered — the window kept itself open" {:con con-id})
    (swap! wintents* dissoc con-id)
    true))


(defn- act!
  "ALL world mutations live here: placement, the event's own action, the
   stranded-rescue. Truthy when anything changed."
  [ev {:keys [ws view]}]
  (let [placed?  (enforce-placement! ws)
        acted?   (case (:type ev)
                   :app/run           (do-run! view (:app ev))
                   :app/close-focused (do-close-focused! view)
                   :recheck/proc      (expire-proc! ev)
                   :recheck/window    (expire-window-intent! ev)
                   nil)
        ;; rescue reacts to WORLD-initiated changes (ticks) only: an act that just
        ;; moved focus must not be undone by the stale pre-act view
        rescued? (when-not (or placed? acted?)
                   (rescue-stranded! view))]
    (or placed? acted? rescued?)))


(defn handle-event!
  "The single entry, the only thinking place: look, act, look again when anything
   changed, publish."
  [ev]
  (let [{:keys [view] :as world} (look!)]
    (publish! (if (act! ev world)
                (:view (look!))
                view))))


;; --- the verbs: validate what must fail loudly, emit the rest onto the pipe ---

(defn require-valid-app! [{:keys [id exec class] :as app}]
  (when (nil? app)
    (throw (ex-info "unknown app" {:error :app/unknown-app})))

  (when-not (and id (vector? exec) (seq exec) (string? class))
    (throw (ex-info "app map needs :id, :exec and :class" {:error :app/invalid-app :app app})))

  true)


(defn run!
  "Validate, then ride the pipe — gating and spawning happen on the listener
   thread, queue-ordered with the window events."
  [app]
  (require-valid-app! app)
  (i3/emit! {:type :app/run :app app}))


(defn run-from-catalog!
  "Resolve ID in the catalog (unknown fails loudly) and run!."
  [id]
  (let [app (get-in @catalog* [:by-id id])]
    (when-not app
      (throw (ex-info "unknown app" {:error :app/unknown-app :id id})))
    (run! app)))


(defn close-focused!
  "Ask the pipe to close the focused window."
  []
  (i3/emit! {:type :app/close-focused}))


(defn go-home!
  "Switch to the launcher's workspace (no state — direct)."
  []
  (i3/switch-workspace! home-workspace))
