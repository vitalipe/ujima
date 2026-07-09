(ns ujima.desktop.app.windows
  "The WINDOW plane: window facts flattened fresh from the tree (never stored),
   per-window close-intents {con-id -> asked-at}, placement. All pure; the live
   intents atom is ujima.desktop.app's."
  (:require [clojure.string :as str]))


(defn from-tree
  "The i3 tree flattened to window facts, with placement context:
   [{:con-id n :class :title :focused? :fullscreen? :workspace :floating? :transient?} ...]."
  [tree]
  (letfn [(walk [node ws floating?]
            (let [ws (if (= "workspace" (:type node)) (:name node) ws)]
              (concat
                (when (:window node)
                  [{:con-id      (:id node)
                    :class       (get-in node [:window_properties :class])
                    :title       (:name node)
                    :focused?    (boolean (:focused node))
                    :fullscreen? (pos? (long (or (:fullscreen_mode node) 0)))
                    :workspace   ws
                    :floating?   floating?
                    :transient?  (some? (get-in node [:window_properties :transient_for]))}])
                (mapcat #(walk % ws floating?) (:nodes node))
                (mapcat #(walk % ws true) (:floating_nodes node)))))]
    (vec (walk tree nil false))))


(defn of-class
  "The windows carrying CLASS (case-insensitive — i3 reports TuxPaint, Pcmanfm, …)."
  [ws class]
  (filterv #(and (:class %) (= (str/lower-case (:class %)) (str/lower-case class))) ws))


(defn apps-present
  "The catalog app-ids that have at least one window in WS."
  [catalog ws]
  (into #{}
        (keep (fn [w] (when (:class w)
                        (:id (get-in catalog [:class->app (str/lower-case (:class w))])))))
        ws))


(defn resolve-intents
  "Prune intents the tree answered: con gone = close done. A surviving con keeps
   its intent (quit-confirm; the :recheck/window echo owns expiry)."
  [wintents ws]
  (let [alive (into #{} (map :con-id) ws)]
    (into {} (filter (fn [[con _at]] (alive con))) wintents)))


(defn to-place
  "The placement plan: a catalog app's non-transient window that floats (chromium
   --app trips the pop-up float rule) or sits off its app's workspace (= app id)
   moves there; the launcher (the webview home surface, class ujima-launcher) moves to HOME.
   Dialogs float in peace. Self-quieting."
  [catalog ws home]
  (vec (for [w ws
             :let [target (when (:class w)
                            (if (= "ujima-launcher" (str/lower-case (:class w)))
                              home
                              (some-> (get-in catalog [:class->app (str/lower-case (:class w))])
                                      :id
                                      name)))]
             :when (and target
                        (not (:transient? w))
                        (or (:floating? w)
                            (not= target (:workspace w))))]
         {:con-id (:con-id w) :workspace target})))
