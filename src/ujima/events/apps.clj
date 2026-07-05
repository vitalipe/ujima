(ns ujima.events.apps
  "Window-event policy: any i3 window event is just a tick — the tree is the state,
   desktop.app re-derives and publishes. Mechanism (subscribe, baseline, normalize)
   lives in ujima.linux.i3."
  (:require [ujima.desktop.app :as app]))


(defn on-window-event! [_ev]
  (app/tick!))
