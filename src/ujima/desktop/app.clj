(ns ujima.desktop.app
  "The app layer model (tmp/app-model/index.md), two halves of one domain:

   Catalog — apps.edn launch specs loaded once at init, indexed by :id. Sugar for the
   HTTP/eww edge to name apps by id; the executor never consults it. Reboot = the seed.

   Proc store — a pure reducer folding normalized i3 window events (ujima.linux.i3)
   into procs {:app-id :pid :windows #{con-id} :state :title}, singleton per app id.
   Identity is the WM_CLASS we control: natives declare :class, stamped apps resolve to
   ujima-<id> (:class-flag, applied at spawn in the startup slice — until then :pid stays
   nil and procs exist only through window adoption). The events listener thread is the
   store's single writer; http reads snapshots."
  (:require [clojure.string :as str]
            [babashka.fs    :as fs]
            [lib.io         :as io]))


;; --- catalog -----------------------------------------------------------------

(defn window-class
  "The WM_CLASS this app's windows are adopted by: a declared natural :class (natives),
   else the stamped ujima-<id>."
  [app]
  (or (:class app) (str "ujima-" (name (:id app)))))


(defn- validate!
  "Loud structural check — a bad baked catalog is a build error, not a runtime surprise."
  [apps]
  (doseq [{:keys [id label exec] :as app} apps]
    (when-not id    (throw (ex-info "catalog app missing :id" {:app app})))
    (when-not label (throw (ex-info "catalog app missing :label" {:id id})))
    (when-not (and (vector? exec) (seq exec))
      (throw (ex-info "catalog app missing :exec" {:id id})))
    (when (and (:class app) (:class-flag app))
      (throw (ex-info "catalog app declares both :class and :class-flag" {:id id}))))
  (let [ids (map :id apps)]
    (when (not= (count ids) (count (distinct ids)))
      (throw (ex-info "catalog has duplicate app :id" {:ids ids}))))
  (let [classes (map (comp str/lower-case window-class) apps)]
    (when (not= (count classes) (count (distinct classes)))
      (throw (ex-info "catalog apps share a WM_CLASS" {:classes classes}))))
  apps)


(defn ->catalog
  "Index raw {:apps [...]} edn: creation order, by-id, and the lower-cased WM_CLASS ->
   app-id adoption index (casing varies by app — i3 reports TuxPaint, Pcmanfm, …). Pure;
   validates loudly."
  [raw]
  (let [apps (validate! (vec (:apps raw)))]
    {:order     (mapv :id apps)
     :by-id     (into {} (map (juxt :id identity)) apps)
     :class->id (into {} (map (fn [a] [(str/lower-case (window-class a)) (:id a)])) apps)}))


(defn listing
  "The GET /app/catalog projection: [{:id :label :icon}] in catalog order."
  [catalog]
  (mapv (fn [id]
          (let [a (get-in catalog [:by-id id])]
            {:id id :label (:label a) :icon (or (:icon a) (name id))}))
        (:order catalog)))


;; --- proc store: a pure reducer over normalized window/proc events -------------

(defn init-procs [catalog]
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


;; --- the live edge: one catalog, one store -------------------------------------

(defonce ^:private catalog* (atom nil))
(defonce ^:private procs*   (atom (init-procs nil)))


(defn load-catalog!
  "Load + index apps.edn from PATH into the live catalog and seed the proc store.
   A missing or unreadable file throws — a broken image, not an empty desktop."
  [path]
  (when-not (and path (fs/exists? (str path)))
    (throw (ex-info "app catalog not found" {:path (str path)})))
  (let [raw (io/slurp-edn path)]
    (when-not (map? raw)
      (throw (ex-info "app catalog unreadable" {:path (str path)})))
    (let [cat (->catalog raw)]
      (reset! catalog* cat)
      (reset! procs* (init-procs cat))
      cat)))


(defn catalog-listing [] (listing @catalog*))
(defn handle-event!  [ev] (swap! procs* apply-event ev))
(defn procs-snapshot []   (snapshot @procs*))
