(ns ujima.desktop.app.lifecycle
  "The APP plane — it owns NOTHING. Each app's state is a pure join:
     :running  windows of its class exist
     :new      no windows, a spawn still awaits its first window
     :closed   neither
   Close-intents don't surface here: closing one window never dirties its app."
  (:require [clojure.string :as str]
            [ujima.desktop.app.windows :as windows]
            [ujima.desktop.app.procs   :as procs]))


(defn view
  "Per-app states + the focused app + the raw focused window fact. Pure."
  [catalog ws registry]
  (let [focused (some #(when (:focused? %) %) ws)
        current (when-let [c (:class focused)]
                  (:id (get-in catalog [:class->app (str/lower-case c)])))
        apps    (mapv (fn [id]
                        (let [app  (get-in catalog [:by-id id])
                              wins (windows/of-class ws (:class app))]
                          {:id      id
                           :label   (:label app)
                           :icon    (or (:icon app) (name id))
                           :state   (cond
                                      (seq wins)                    :running
                                      (procs/awaiting? registry id) :new
                                      :else                         :closed)
                           :title   (:title (or (some #(when (:focused? %) %) wins)
                                                (first wins)))
                           :windows (mapv :con-id wins)}))
                      (:order catalog))]
    {:apps    apps
     :current current
     :focused focused}))


(defn state-of [view id]
  (:state (some #(when (= id (:id %)) %) (:apps view))))


(defn snapshot
  "The wire shape (lib.edn camelCases keys): non-:closed apps in catalog order,
   the focused app, and its title."
  [view]
  (let [visible (filterv #(not= :closed (:state %)) (:apps view))
        current (:current view)]
    {:apps          (mapv #(dissoc % :windows) visible)
     :current       current
     :current-title (:title (some #(when (= current (:id %)) %) visible))}))
