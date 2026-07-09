(ns ujima.desktop.app
  "A contained converging system over the window stream: evt -> look -> act -> look -> converge!,
   which fans (next prv) out to the converge-targets (installed by init!). Three planes own their
   state: windows (close-intents), procs (the spawn registry), lifecycle (a pure join)."
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
(defonce ^:private wintents* (atom {}))   ; the window plane's state: con-id -> asked-at
(defonce ^:private procs*    (atom {}))   ; the proc plane's state: the spawn registry
(defonce ^:private prev*    (atom nil))  ; the last snapshot emitted — the prv of (next prv)
(defonce ^:private targets* (atom []))   ; converge targets [(fn [next prv]) …], installed by init!


(def ^:private staging-workspace "ujima-loading")  ; spawn maps here, not splitting the launcher
(def ^:private home-workspace    "1")              ; where eww's launcher window lives


(defn load-catalog
  "Load + index apps.edn, returning the catalog. Missing or unreadable throws — a broken image,
   not an empty desktop. Doesn't touch the plane's state; init! installs the result."
  [path]
  (when-not (and path (fs/exists? (str path)))
    (throw (ex-info "app catalog not found" {:path (str path)})))
  (let [raw (io/slurp-edn path)]
    (when-not (map? raw)
      (throw (ex-info "app catalog unreadable" {:path (str path)})))
    (catalog/->catalog raw)))


(defn init!
  "Install the loaded :catalog and the :converge-targets [(fn [next prv]) …] each converge fans
   out to; reset the plane's ledgers."
  [{:keys [catalog converge-targets]}]
  (reset! catalog*  catalog)
  (reset! wintents* {})
  (reset! procs*    {})
  (reset! prev*     nil)
  (reset! targets*  (vec converge-targets))
  catalog)


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


(defn- converge!
  "The pipeline tail: snapshot the view, fan (next prv) out to the targets, remember next as prv."
  [view]
  (let [next (lc/snapshot view)
        prv  @prev*]
    (reset! prev* next)
    (doseq [target @targets*] (target next prv))))


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
  "The single entry, the only thinking place: evt -> look -> act -> look -> converge!."
  [ev]
  (let [world (look!)
        view  (if (act! ev world) (:view (look!)) (:view world))]
    (converge! view)))


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
