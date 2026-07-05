(ns ujima.desktop.app.proc
  "The per-app state machine, derived — never folded. The i3 tree is the state; we keep
   only what i3 can't know (side-intents): :new = we spawned and await the window,
   :closing = we sent WM_close to a con and await its removal. Each app's :state is a
   pure function of (tree windows, side-intents):

     :closed  no window with the app's class          (and no :new intent)
     :new     no window yet, but we just spawned      (intent; timers resolve it)
     :running window(s) present                       (presence clears :new)
     :closing window still present after WM_close     (absence clears :closing)

   This derivation is what makes LibreOffice adoptable: its settled WM_CLASS never rides
   any i3 event, but it IS in the tree. Pure; the live side atom, the timers and the
   spawns are ujima.desktop.app's."
  (:require [clojure.string :as str]))


(defn windows
  "The i3 tree flattened to the windows we reason about, with placement context:
   [{:con-id n :class :title :focused? :workspace :floating? :transient?} ...]. Pure."
  [tree]
  (letfn [(walk [node ws floating?]
            (let [ws (if (= "workspace" (:type node)) (:name node) ws)]
              (concat
                (when (:window node)
                  [{:con-id     (:id node)
                    :class      (get-in node [:window_properties :class])
                    :title      (:name node)
                    :focused?   (boolean (:focused node))
                    :workspace  ws
                    :floating?  floating?
                    :transient? (some? (get-in node [:window_properties :transient_for]))}])
                (mapcat #(walk % ws floating?) (:nodes node))
                (mapcat #(walk % ws true) (:floating_nodes node)))))]
    (vec (walk tree nil false))))


(defn- windows-of [ws class]
  (filterv #(and (:class %) (= (str/lower-case (:class %)) (str/lower-case class))) ws))


(defn app-state
  "One app's SM state from its windows and its side-intent (:new | :closing | nil)."
  [app-windows intent]
  (cond
    (seq app-windows) (if (= :closing intent) :closing :running)
    (= :new intent)   :new
    :else             :closed))


(defn derive-view
  "The whole model, derived: per-app states + the focused app + the wire snapshot.
   CATALOG = the loaded {:order :by-id :class->app}; WS = (windows tree);
   SIDE = {app-id {:phase :new|:closing :con n :at ms :pid n}}. Pure."
  [catalog ws side]
  (let [class->id (into {} (map (fn [[c a]] [c (:id a)])) (:class->app catalog))
        focused   (some #(when (:focused? %) %) ws)
        current   (when-let [c (:class focused)] (get class->id (str/lower-case c)))
        apps      (mapv (fn [id]
                          (let [app  (get-in catalog [:by-id id])
                                wins (windows-of ws (:class app))
                                st   (app-state wins (get-in side [id :phase]))]
                            {:id      id
                             :label   (:label app)
                             :icon    (or (:icon app) (name id))
                             :state   st
                             :title   (:title (or (some #(when (:focused? %) %) wins)
                                                  (first wins)))
                             :windows (mapv :con-id wins)}))
                        (:order catalog))]
    {:apps    apps
     :current current
     :focused focused}))


(defn to-place
  "The placement plan (desktop-base's place!, derived): every non-transient window of
   a catalog app that floats (chromium --app trips the i3 pop-up float rule) or sits
   off its app's workspace (workspace name = the app id) must move — and so does the
   launcher (eww makes its one managed window floating AND sticky, so untouched it
   shadows every workspace, stacking above the tiled apps), which belongs tiled on
   HOME. Dialogs stay floating where i3 put them. Pure — self-quieting: a placed
   window stops matching."
  [catalog ws home]
  (vec (for [w ws
             :let [target (when (:class w)
                            (if (= "eww" (str/lower-case (:class w)))
                              home
                              (some-> (get-in catalog [:class->app (str/lower-case (:class w))])
                                      :id
                                      name)))]
             :when (and target
                        (not (:transient? w))
                        (or (:floating? w)
                            (not= target (:workspace w))))]
         {:con-id (:con-id w) :workspace target})))


(defn resolve-side
  "Which side-intents the tree has answered: :new with a window present is done
   (New -> Running), :closing whose con is gone is done (Closing -> Closed).
   Returns the pruned side map. Pure — the edge swaps it in on every derive."
  [catalog ws side]
  (reduce-kv (fn [m id {:keys [phase con] :as intent}]
               (let [wins (windows-of ws (:class (get-in catalog [:by-id id])))]
                 (cond
                   (and (= :new phase) (seq wins))                              m  ; window arrived
                   (and (= :closing phase) (not-any? #(= con (:con-id %)) wins)) m ; that window gone
                   :else (assoc m id intent))))
             {}
             side))


(defn snapshot
  "The /ui/apps wire shape (lib.edn camelCases keys): non-:closed apps in catalog
   order, the focused app, and the focused app's title resolved for the topbar."
  [view]
  (let [visible (filterv #(not= :closed (:state %)) (:apps view))
        current (:current view)]
    {:apps          (mapv #(dissoc % :windows) visible)
     :current       current
     :current-title (:title (some #(when (= current (:id %)) %) visible))}))
