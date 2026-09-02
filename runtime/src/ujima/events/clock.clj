(ns ujima.events.clock
  "The software RTC: witnessed time becomes the next boot's clock floor."
  (:require [ujima.control.commands :as commands]))


(def ^:private floor-key [:system :clock :epoch-floor])


(defn on-tick!
  "Record AT as the epoch floor."
  [{:keys [at]}]
  (commands/change-setting! floor-key at :device))
