(ns ujima.events.apps
  "App-plane policy: the window stream carries everything — real i3 window events
   and the :recheck/* self-events the app asked i3 to echo back — and every one of
   them goes to desktop.app's single entry. Mechanism lives in ujima.linux.i3."
  (:require [ujima.desktop.app :as app]))


(defn on-event! [ev]
  (app/handle-event! ev))
