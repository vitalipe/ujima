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


(defonce ^:private catalog*    (atom nil))
(defonce ^:private wintents*   (atom {}))   ; the window plane's state: con-id -> asked-at
(defonce ^:private procs*      (atom {}))   ; the proc plane's state: the spawn registry
(defonce ^:private push*       (atom nil))  ; the GUI edge, wired by core (set-push!)
(defonce ^:private bars*           (atom nil)) ; the eww bar control (fn [show?]), wired by core (set-bars!)
(defonce ^:private bars-hidden-for* (atom nil)) ; app-id the bars are hidden FOR (nil = shown); latched
                                                ; so an app's own fullscreen flapping can't thrash them


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
      (reset! bars-hidden-for* nil)
      cat)))


(defn set-push!
  "Install the GUI edge (wired by core)."
  [f]
  (reset! push* f))


(defn set-bars!
  "Install the eww bar control (wired by core): (fn [show?]) opens/closes the top bar + dock."
  [f]
  (reset! bars* f))


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


(defn- reconcile-bars!
  "Hide the eww bars for a fullscreen app, show them otherwise — eww can't unmap itself, so
   the agent owns it. LATCHED per focused app: toggling the override-redirect bars perturbs an
   SDL app's own fullscreen (tuxtype flaps it, which would otherwise feedback-loop the bars), so
   once hidden for an app we stay hidden through its flapping until focus leaves it (nil = the
   launcher, or another non-fullscreen app)."
  [view]
  (let [cur-app (:id (:current view))
        hide?   (boolean (and cur-app (or (:fullscreen? (:focused view))
                                          (= cur-app @bars-hidden-for*))))]
    (when @bars*
      (cond
        (and hide? (nil? @bars-hidden-for*))  (do (@bars* false) (reset! bars-hidden-for* cur-app))
        (and (not hide?) @bars-hidden-for*)   (do (@bars* true)  (reset! bars-hidden-for* nil))))))


;; --- the act phase (listener thread only; each returns truthy iff it changed the world) ---

(defn- enforce-placement!
  "Move strays per the plan."
  [ws]
  (let [plan (windows/to-place @catalog* ws home-workspace)]
    (doseq [{:keys [con-id workspace]} plan]
      (i3/place! con-id workspace))
    (boolean (seq plan))))


(defn- rescue-stranded!
  "The focused workspace is DEAD — zero windows, i3 never leaves one by itself ->
   home. Not \"no managed window focused\": that is also true mid focus-handoff and
   on unmanaged windows (LO's Start Center), where yanking would be wrong. Staging
   is the loading wait; the proc recheck owns it."
  [{:keys [ws view]}]
  (when (nil? (:current view))                       ; fast path: managed focus = alive
    (let [fws (i3/focused-workspace)]
      (when (and (not (#{home-workspace staging-workspace} fws))
                 (not-any? #(= fws (:workspace %)) ws))
        (i3/switch-workspace! home-workspace)
        true))))

(defn- hint-proc!
  "Echo a proc-plane recheck: did APP-ID's spawn (identified by AT = its
   :spawned-at) ever produce a window? 25s — LibreOffice needs ~20s on the Pi."
  [app-id at]
  (i3/emit-in! 25000 {:type :recheck/proc :app-id app-id :at at}))


(defn- hint-window!
  "Echo a window-plane recheck: did CON-ID (close asked at AT) actually close?
   Past 10s a quit-confirm is holding it."
  [con-id at]
  (i3/emit-in! 10000 {:type :recheck/window :con-id con-id :at at}))


(defn- do-run!
  "Gate on the derived SM: :running -> focus (ensure-open), :new -> no-op,
   :closed -> spawn onto staging; placement hands it to its own workspace, and
   a THROWING spawn rescues straight home."
  [view {:keys [id exec] :as app}]
  (case (lc/state-of view id)
    :running (do (log/info "app already open — focusing" {:app id})
                 (i3/switch-workspace! (name id))
                 true)     ; moved focus = acted
    :new     (do (log/info "run gated — still opening" {:app id})
                 false)
    ;; :shutdown — no orphans if the agent dies; :inherit — an unread pipe would fill
    (do (i3/switch-workspace! staging-workspace)
        (try
          (let [proc (apply shell/sh {:out :inherit :err :inherit :shutdown p/destroy-tree} exec)
                pid  (try (.pid (:proc proc)) (catch Throwable _ nil))
                at   (System/currentTimeMillis)]
            (swap! procs* assoc id {:handle proc :pid pid :spawned-at at})
            (hint-proc! id at)
            (log/info "app spawned" {:app id :pid pid})
            true)
          (catch Throwable e
            ;; nothing registered, no recheck armed — rescue here or nowhere
            (log/error "app spawn failed" {:app id :error (ex-message e)})
            (i3/switch-workspace! home-workspace)
            true)))))


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
        (hint-window! con-id at)
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
  [ev {:keys [ws view] :as world}]
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
                   (rescue-stranded! world))]
    (or placed? acted? rescued?)))


(defn handle-event!
  "The single entry, the only thinking place: look, act, look again when anything
   changed, reconcile the bars against focus, publish."
  [ev]
  (let [world (look!)
        view  (if (act! ev world) (:view (look!)) (:view world))]
    (reconcile-bars! view)
    (publish! view)))


;; --- the verbs: resolve what must fail loudly, emit the rest onto the pipe ---

(defn run!
  "Resolve ID in the catalog and ride the pipe — gating and spawning happen on
   the listener thread, queue-ordered with the window events. Catalog membership
   IS the validation: the model adopts by cataloged class, so an uncataloged
   spawn would never mark windowed and its own recheck would kill it."
  [id]
  (let [app (get-in @catalog* [:by-id id])]
    (when-not app
      (throw (ex-info "unknown app" {:error :app/unknown-app :id id})))
    (i3/emit! {:type :app/run :app app})))


(defn close-focused!
  "Ask the pipe to close the focused window."
  []
  (i3/emit! {:type :app/close-focused}))


(defn go-home!
  "Switch to the launcher's workspace (no state — direct)."
  []
  (i3/switch-workspace! home-workspace))
