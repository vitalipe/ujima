(ns ujima.desktop.app
  "The app domain's live edge: the i3 tree is the state, this ns keeps only the
   side-intents i3 can't know (app.proc derives the per-app SM from both) plus the
   process handles. Every mutation and every window event funnels into converge! —
   fresh tree, resolve answered intents, publish (core wires the GUI push via
   set-push!). run! gates on the derived state (only :closed may open); timers are
   the no-event net (a failed spawn re-arms the tile, LibreOffice's silent class
   swap gets seen). Validation/indexing is app.catalog."
  (:require [babashka.fs      :as fs]
            [babashka.process :as p]
            [lib.io    :as io]
            [lib.shell :as shell]
            [ujima.log :as log]
            [ujima.linux.i3            :as i3]
            [ujima.desktop.app.catalog :as catalog]
            [ujima.desktop.app.proc    :as proc]))


(defonce ^:private catalog* (atom nil))
(defonce ^:private side*    (atom {}))   ; app-id -> {:phase :new|:closing :con n :at ms}
(defonce ^:private handles* (atom {}))   ; app-id -> live process handle (pid via (.pid (:proc h)))
(defonce ^:private push*    (atom nil))  ; the GUI edge, wired by core (set-push!)
(defonce ^:private lock     (Object.))   ; one critical section: derive + gate + act


(def ^:private staging-workspace "ujima-loading")  ; spawn maps here, not splitting the launcher
(def ^:private home-workspace    "1")              ; where eww's launcher window lives


(defn load-catalog!
  "Load + index apps.edn from PATH. A missing or unreadable file throws — a broken
   image, not an empty desktop."
  [path]
  (when-not (and path (fs/exists? (str path)))
    (throw (ex-info "app catalog not found" {:path (str path)})))
  (let [raw (io/slurp-edn path)]
    (when-not (map? raw)
      (throw (ex-info "app catalog unreadable" {:path (str path)})))
    (let [cat (catalog/->catalog raw)]
      (reset! catalog* cat)
      (reset! side* {})
      cat)))


(defn set-push!
  "Install the GUI edge (core passes desktop.http.app's push!)."
  [f]
  (reset! push* f))


(defn catalog-listing [] (catalog/listing @catalog*))


(defn- converge!
  "Fresh tree -> resolve answered side-intents -> enforce placement -> derive ->
   publish. Returns the derived view. Every path in this ns funnels here; the lock
   is reentrant. Placement is desired-vs-actual and self-quieting: a placed window
   stops matching to-place, so steady-state ticks enforce nothing."
  []
  (locking lock
    (let [ws     (proc/windows (i3/get-tree!))
          placed (proc/to-place @catalog* ws home-workspace)]
      (doseq [{:keys [con-id workspace]} placed]
        (i3/place! con-id workspace))
      (let [ws   (if (seq placed) (proc/windows (i3/get-tree!)) ws)
            side (swap! side* #(proc/resolve-side @catalog* ws %))
            view (proc/derive-view @catalog* ws side)]
        (when-let [push! @push*]
          (push! (proc/snapshot view)))
        ;; nothing of ours focused and the user is on a dead app workspace (its last
        ;; window just closed — i3 won't leave it by itself) -> return home. Staging is
        ;; exempt: that's the loading wait, the :new recheck owns rescuing it.
        (when (nil? (:current view))
          (let [fws (i3/focused-workspace)]
            (when-not (#{home-workspace staging-workspace} fws)
              (i3/switch-workspace! home-workspace))))
        view))))


(defn snapshot-now
  "The current wire snapshot (the stream's on-connect line)."
  []
  (proc/snapshot (converge!)))


(defn handle-event!
  "The single event entry — everything the window stream carries lands here via
   ujima.events. Any event is a tick: converge from a fresh tree. The synthetic
   rechecks (i3/emit-in! echoes them back for us) additionally expire the EXACT
   intent they were armed for (:app-id + :at) when the tree still hasn't answered
   it: a :new that never windowed re-arms the tile (and rescues the user from the
   empty staging workspace), a :closing held by a quit-confirm honestly reverts
   to :running."
  [ev]
  (locking lock
    (converge!)
    (when-let [phase ({:recheck/opening :new
                       :recheck/closing :closing} (:type ev))]
      (let [{:keys [app-id at]} ev]
        (when (= {:phase phase :at at}
                 (select-keys (get @side* app-id) [:phase :at]))
          (log/warn "app intent expired" {:app app-id :phase phase})
          (swap! side* dissoc app-id)
          (when (and (= :new phase) (= staging-workspace (i3/focused-workspace)))
            (i3/switch-workspace! home-workspace))
          (converge!))))))


(defn require-valid-app! [{:keys [id exec class] :as app}]
  (when (nil? app)
    (throw (ex-info "unknown app" {:error :app/unknown-app})))

  (when-not (and id (vector? exec) (seq exec) (string? class))
    (throw (ex-info "app map needs :id, :exec and :class" {:error :app/invalid-app :app app})))

  true)


(defn- app-state [view id]
  (:state (some #(when (= id (:id %)) %) (:apps view))))


(defn run!
  "The run command, gated by the derived SM: a :running app gets FOCUSED (ensure-open
   — the dock button and the launcher tile are the same verb), :new/:closing no-op
   (can't open while opening or closing), only :closed spawns — onto the staging
   workspace, so the window maps there instead of splitting the launcher; converge's
   placement then hands it to its own workspace and switches to it."
  [{:keys [id exec] :as app}]
  (require-valid-app! app)
  (locking lock
    (let [st (app-state (converge!) id)]
      (case st
        :running (do (log/info "app already open — focusing" {:app id})
                     (i3/switch-workspace! (name id)))
        (:new :closing) (log/info "run gated" {:app id :state st})
        ;; :shutdown — no orphaned apps if the agent dies outside a clean session teardown;
        ;; :inherit — app stdio goes to the journal like eww's (an unread pipe would fill)
        (let [_      (i3/switch-workspace! staging-workspace)
              proc   (apply shell/sh {:out :inherit :err :inherit :shutdown p/destroy-tree} exec)
              pid    (try (.pid (:proc proc)) (catch Throwable _ nil))
              intent {:phase :new :at (System/currentTimeMillis)}]
          
          (swap! handles* assoc id proc)
          (swap! side* assoc id intent)
          (i3/hint-open! id (:at intent))
          
          (log/info "app spawned" {:app id :pid pid})
          (converge!))))))


(defn run-from-catalog!
  "POST /app/run's verb: resolve ID in the catalog and hand the map to run!."
  [id]
  (let [app (get-in @catalog* [:by-id id])]
    (when-not app
      (throw (ex-info "unknown app" {:error :app/unknown-app :id id})))
    (run! app)))


(defn go-home!
  "POST /app/home's verb: switch to the launcher's workspace."
  []
  (i3/switch-workspace! home-workspace))


(defn close-focused!
  "POST /app/close's verb: WM_close to the FOCUSED window (not the app — one
   LibreOffice doc closes, the app lives while other windows do). The agent
   resolves focus from the tree itself; an unmanaged focus is a no-op."
  []
  (locking lock
    (let [view    (converge!)
          focused (:focused view)
          app-id  (:current view)]
      (if-not (and focused app-id)
        (log/info "close gated — no managed window focused" {})
        (let [intent {:phase :closing
                      :con   (:con-id focused)
                      :at    (System/currentTimeMillis)}]
          (swap! side* assoc app-id intent)
          
          (i3/kill-con! (:con-id focused))
          (i3/hint-close! app-id (:at intent))
          (log/info "close sent" {:app app-id :con (:con-id focused)})
          
          (converge!))))))
