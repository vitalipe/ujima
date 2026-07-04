(ns ujima.desktop.app.proc
  "The proc store — a pure reducer folding normalized i3 window events (ujima.linux.i3)
   into procs {:app-id :pid :windows #{con-id} :state :title}, singleton per app id.
   Identity is the WM_CLASS the catalog names; :pid arrives with start-app! (the startup
   slice) — until then procs exist only through window adoption. Pure; the live atom and
   its single writer are ujima.desktop.app's."
  (:require [clojure.string :as str]))


(defn init [catalog]
  {:catalog catalog
   :procs   {}      ; app-id -> proc
   :order   []      ; app-id adoption order (dock order)
   :wm->app {}      ; con-id -> app-id
   :current nil})   ; focused app, nil while an unmanaged window (or nothing) has focus


(defn- adopt
  "Join CON-ID to the app its WM_CLASS names. Extra windows of a tracked app attach to
   its proc; a transient (dialog) never *creates* one — it only attaches (LibreOffice's
   Tip-of-the-Day is born carrying the writer class). Tracked cons and unknown classes
   pass through, so replays are idempotent."
  [state {:keys [con-id class transient? title]}]
  (let [app-id (when class (get-in state [:catalog :class->id (str/lower-case class)]))]
    (cond
      (get-in state [:wm->app con-id]) state
      (nil? app-id)                    state

      (get-in state [:procs app-id])
      (-> state
          (update-in [:procs app-id :windows] conj con-id)
          (assoc-in  [:wm->app con-id] app-id))

      transient? state

      :else
      (-> state
          (assoc-in [:procs app-id] {:app-id  app-id
                                     :pid     nil     ; start-app! stamps it (startup slice)
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


(defn- on-exit
  "A spawned pid died (the startup slice wires the producer). Its windows follow with
   their own close events; the mark is what the crash taxonomy reads."
  [state {:keys [app-id]}]
  (cond-> state
    (get-in state [:procs app-id]) (assoc-in [:procs app-id :state] :exited)))


(defn apply-event
  "Fold one normalized event into the store. Pure; unknown types pass through."
  [state event]
  (case (:type event)
    :window/new   (adopt    state event)
    :window/title (on-title state event)
    :window/close (on-close state event)
    :window/focus (on-focus state event)
    :proc/exit    (on-exit  state event)
    state))


(defn snapshot
  "The /ui/apps wire shape (lib.edn camelCases keys on the way out)."
  [state]
  {:apps    (mapv (fn [id]
                    (let [p (get-in state [:procs id])
                          a (get-in state [:catalog :by-id id])]
                      {:id    id
                       :label (:label a)
                       :icon  (or (:icon a) (name id))
                       :state (:state p)
                       :title (:title p)}))
                  (:order state))
   :current (:current state)})
