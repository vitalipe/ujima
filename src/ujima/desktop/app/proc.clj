(ns ujima.desktop.app.proc
  "The proc store — a pure reducer folding normalized i3 window events (ujima.linux.i3)
   and proc events into procs {:app-id :app :pid :windows #{con-id} :state :title},
   singleton per app id. Identity is the app map's :class via the catalog-seeded
   :class->app index — set once at load, immutable for the session (a mutable catalog
   would live in settings and converge, not here). :pid comes from run!'s spawn;
   adoption-created procs (a window we recognize but didn't spawn) carry :pid nil.
   Pure; the live atom and its writers are ujima.desktop.app's."
  (:require [clojure.string :as str]))


(defn init [class->app]
  {:class->app (or class->app {})   ; lower-cased WM_CLASS -> app map (catalog seed + learned)
   :procs      {}                   ; app-id -> proc
   :order      []                   ; app-id adoption order (dock order)
   :wm->app    {}                   ; con-id -> app-id
   :current    nil})                ; focused app, nil while an unmanaged window has focus


(defn- adopt
  "Join CON-ID to the app its WM_CLASS names. Extra windows of a tracked app attach to
   its proc (promoting :starting -> :running — the spawned window arrived); a transient
   (dialog) never *creates* a proc — it only attaches (LibreOffice's Tip-of-the-Day is
   born carrying the writer class). Tracked cons and unknown classes pass through, so
   replays are idempotent."
  [state {:keys [con-id class transient? title]}]
  (let [app    (when class (get-in state [:class->app (str/lower-case class)]))
        app-id (:id app)]
    (cond
      (get-in state [:wm->app con-id]) state
      (nil? app-id)                    state

      (get-in state [:procs app-id])
      (-> state
          (update-in [:procs app-id]
                     (fn [p] (cond-> p
                               (= :starting (:state p)) (assoc :state :running)  ; New -> Running
                               (nil? (:title p))         (assoc :title title))))
          (update-in [:procs app-id :windows] conj con-id)
          (assoc-in  [:wm->app con-id] app-id))

      transient? state

      :else
      (-> state
          (assoc-in [:procs app-id] {:app-id  app-id
                                     :app     app
                                     :pid     nil     ; a window we recognize but didn't spawn
                                     :windows #{con-id}
                                     :state   :running
                                     :title   title})
          (update :order conj app-id)
          (assoc-in [:wm->app con-id] app-id)
          (assoc :current app-id)))))


(defn- on-title [state {:keys [con-id title] :as ev}]
  (if-let [app-id (get-in state [:wm->app con-id])]
    (assoc-in state [:procs app-id :title] title)
    (adopt state ev)))    ; untracked — its class may have arrived late (LibreOffice)


(defn- on-close [state {:keys [con-id]}]
  (if-let [app-id (get-in state [:wm->app con-id])]
    (let [state (-> state
                    (update-in [:procs app-id :windows] disj con-id)
                    (update :wm->app dissoc con-id))]
      (if (seq (get-in state [:procs app-id :windows]))
        state    ; a dialog closing keeps the proc alive
        (-> state
            (update :procs dissoc app-id)
            (update :order #(vec (remove #{app-id} %)))
            (cond-> (= app-id (:current state)) (assoc :current nil)))))
    state))


(defn- on-focus [state {:keys [con-id]}]
  (assoc state :current (get-in state [:wm->app con-id])))


(defn- on-started
  "run! spawned APP — the proc enters the lifecycle at :starting (New): in the dock
   from click time, no windows yet; adoption promotes it to :running. An existing proc
   only gets :pid/:app refreshed (run! is ensure-open, so that's a defensive replay
   path)."
  [state {:keys [app pid]}]
  (let [app-id (:id app)]
    (if (get-in state [:procs app-id])
      (update-in state [:procs app-id] assoc :pid pid :app app)
      (-> state
          (assoc-in [:procs app-id] {:app-id  app-id
                                     :app     app
                                     :pid     pid
                                     :windows #{}
                                     :state   :starting
                                     :title   nil})
          (update :order conj app-id)))))


(defn- on-exit
  "The spawned pid died. Its windows follow with their own close events; the mark is
   what the crash taxonomy reads."
  [state {:keys [app-id]}]
  (cond-> state
    (get-in state [:procs app-id]) (assoc-in [:procs app-id :state] :exited)))


(defn apply-event
  "Fold one normalized event into the store. Pure; unknown types pass through."
  [state event]
  (case (:type event)
    :window/new   (adopt      state event)
    :window/title (on-title   state event)
    :window/close (on-close   state event)
    :window/focus (on-focus   state event)
    :proc/started (on-started state event)
    :proc/exit    (on-exit    state event)
    state))


(defn snapshot
  "The /ui/apps wire shape (lib.edn camelCases keys on the way out). Everything renders
   from the proc's own :app — the store needs no catalog."
  [state]
  {:apps    (mapv (fn [id]
                    (let [p (get-in state [:procs id])]
                      {:id    id
                       :label (get-in p [:app :label])
                       :icon  (or (get-in p [:app :icon]) (name id))
                       :state (:state p)
                       :title (:title p)}))
                  (:order state))
   :current (:current state)})
