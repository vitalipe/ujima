(ns ujima.events.apps
  "Window-event policy: fold the i3 stream into the proc store and publish the apps
   snapshot. Mechanism (subscribe, baseline, normalize) lives in ujima.linux.i3; the
   listener thread here is the store's single writer, so fold-then-push needs no lock."
  (:require [ujima.desktop.app :as app]
            [ujima.desktop.ui  :as ui]))


(defn on-window-event!
  "Fold one window/proc event; push the snapshot (ui dedupes no-op folds)."
  [ev]
  (app/handle-event! ev)
  (ui/push-apps! (app/procs-snapshot)))
