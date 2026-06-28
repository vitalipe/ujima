(ns ujima.desktop.windows
  "The window projection: a pure reducer that folds normalized i3 events into the Ujima-window
   model, the source of truth the dock/topbar render from. State is a *projection of i3's tree* —
   every change arrives as an event (the i3 IPC client in ujima.desktop.i3 normalizes raw i3
   events into these), so an app crash, a graceful close, and our own close all reconcile the
   same way.

   A Ujima window owns an i3 workspace and one-or-more i3 containers (con-ids): the main window
   plus any dialogs/extra frames of the same app. Identity is the i3 container, joined to the
   catalog by WM_CLASS (web apps are stamped ujima-<id>; desktop apps carry their natural class).

   Normalized events (see i3 client):
     {:type :window/new   :con-id n :class \"ujima-wikipedia\" :title \"…\"}
     {:type :window/close :con-id n}
     {:type :window/title :con-id n :title \"…\"}
     {:type :window/focus :con-id n}"
  (:require [clojure.string :as str]
            [ujima.desktop.catalog :as catalog]
            [ujima.desktop.launch  :as launch]))


(defn- class->app-id
  "Static WM_CLASS -> app-id index for the launchable kinds (:shell has no tracked X window).
   Keyed lower-case — WM_CLASS casing varies by app (i3 reports TuxPaint, Pcmanfm, …)."
  [cat]
  (into {} (for [app (catalog/apps cat)
                 :when (#{:web :desktop :shell} (:kind app))]
             [(str/lower-case (launch/window-class app)) (:id app)])))


(defn init-state [cat]
  {:catalog       cat
   :class->app-id (class->app-id cat)
   :windows       {}          ; win-id -> ujima window
   :order         []          ; win-id creation order (dock order)
   :wm->win       {}          ; con-id -> win-id
   :current       :launcher   ; focused Ujima window, or :launcher
   :next-n        1
   :status        :ok})


(defn- win-for-app [state app-id]
  (some (fn [[wid w]] (when (= app-id (:app-id w)) wid)) (:windows state)))


(defn- on-new [state {:keys [con-id class title]}]
  (let [app-id   (when class (get (:class->app-id state) (str/lower-case class)))
        existing (when app-id (win-for-app state app-id))]
    (cond
      ;; unmanaged window (no catalog class) — leave it to i3 (floats), don't track it
      (nil? app-id) state

      ;; another container of an app we already track — attach to its Ujima window
      existing (-> state
                   (update-in [:windows existing :wm-windows] conj con-id)
                   (assoc-in  [:wm->win con-id] existing))

      ;; a new Ujima window: owns a fresh workspace, becomes current
      :else (let [wid (format "win-%04d" (:next-n state))
                  app (catalog/app (:catalog state) app-id)]
              (-> state
                  (assoc-in [:windows wid] {:id         wid
                                            :app-id     app-id
                                            :title      (or title (:label app))
                                            :workspace  wid
                                            :wm-windows #{con-id}
                                            :state      :running})
                  (update   :order conj wid)
                  (assoc-in [:wm->win con-id] wid)
                  (assoc    :current wid)
                  (update   :next-n inc))))))


(defn- on-close [state {:keys [con-id]}]
  (if-let [wid (get-in state [:wm->win con-id])]
    (let [state (-> state
                    (update-in [:windows wid :wm-windows] disj con-id)
                    (update    :wm->win dissoc con-id))]
      ;; the workspace dies only when its LAST container is gone (a save dialog keeps it alive)
      (if (empty? (get-in state [:windows wid :wm-windows]))
        (-> state
            (update :windows dissoc wid)
            (update :order   (fn [o] (vec (remove #{wid} o))))
            (cond-> (= (:current state) wid) (assoc :current :launcher)))
        state))
    state))


(defn- on-title [state {:keys [con-id title]}]
  (if-let [wid (get-in state [:wm->win con-id])]
    (assoc-in state [:windows wid :title] title)
    state))


(defn- on-focus [state {:keys [con-id]}]
  (assoc state :current (get-in state [:wm->win con-id] :launcher)))


(defn apply-event
  "Fold one normalized i3 event into the projection. Pure. Unknown event types pass through
   unchanged (e.g. :window/fullscreen_mode — permissive fullscreen, nothing to police)."
  [state event]
  (case (:type event)
    :window/new   (on-new   state event)
    :window/close (on-close state event)
    :window/title (on-title state event)
    :window/focus (on-focus state event)
    state))


(defn snapshot
  "The wire shape the dock/topbar render from (pushed as NDJSON). Joins each window to its
   catalog entry for the chrome flags. Plain keys (no `?`) — lib.edn camelCases them for JSON."
  [state]
  (let [current (:current state)
        windows (vec (for [id  (:order state)
                           :let [w   (get-in state [:windows id])
                                 app (catalog/app (:catalog state) (:app-id w))]]
                       {:id          (:id w)
                        :app-id      (:app-id w)
                        :title       (if (= :launcher (:app-id w)) (:label app) (:title w))
                        :icon        (or (:icon app) (name (:app-id w)))
                        :show-topbar (boolean (:show-topbar? app))
                        :closable    (boolean (:closable? app))}))]
    {:windows        windows
     :current        current
     :current-window (or (some #(when (= (:id %) current) %) windows)
                         {:show-topbar false :closable false})  ; resolved for the topbar; nil on launcher
     :status         (:status state)}))


;; --- read accessors: the http layer reads the projection; only the i3 event thread writes it ---

(defn window-for-con [state con-id] (get-in state [:wm->win con-id]))
(defn window-for-app [state app-id] (win-for-app state app-id))
(defn window         [state wid]    (get-in state [:windows wid]))
(defn con-ids        [state wid]    (get-in state [:windows wid :wm-windows]))
(defn current        [state]        (:current state))
