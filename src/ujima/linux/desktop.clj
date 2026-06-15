(ns ujima.linux.desktop
  (:require [ujima.linux.shell :refer [sh]]))


(defn volume []
  (let [out (:out (sh :pactl "get-sink-volume" "@DEFAULT_SINK@"))]
    (Integer/parseInt (second (re-find #"(\d+)%" out)))))


(defn volume! [value]
    (let [value (-> value int (max 0) (min 100))]
      (sh :pactl "set-sink-volume" "@DEFAULT_SINK@" (str value "%"))
      (volume)))


(defn mute []
  (let [out (:out (sh :pactl "get-sink-mute" "@DEFAULT_SINK@"))]
    (boolean (re-find #"yes" out))))


(defn mute! [muted?]
  (sh :pactl "set-sink-mute" "@DEFAULT_SINK@" (if muted? "1" "0"))
  (mute))
