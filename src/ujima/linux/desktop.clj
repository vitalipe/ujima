(ns ujima.linux.desktop
  (:require [ujima.linux.shell :refer [sh]]))


(defn volume []
  (let [out (:out (sh :pactl "get-sink-volume" "@DEFAULT_SINK@"))]
    (Integer/parseInt (second (re-find #"(\d+)%" out)))))


(defn volume! [value]
    (let [value (-> value int (max 0) (min 100))]
      (sh :pactl "set-sink-volume" "@DEFAULT_SINK@" (str value "%"))
      (volume)))
