(ns ujima.linux.system
  (:require [clojure.string :as str]
            [ujima.linux.shell :refer [sh sudo]]))            


(defn hostname []
  (:out (sh :hostnamectl "--static")))


(defn hostname! [hostname]
  (sudo :hostnamectl "set-hostname" hostname)
  (hostname))


(defn timezone []
  (:out (sh :timedatectl "show" "-p" "Timezone" "--value")))


(defn timezone! [timezone]
  (sudo :timedatectl "set-timezone" timezone)
  (timezone))


(defn keyboard-layouts []
  []) ;; TODO


(defn keyboard-layouts! [layouts]
  (sudo :localectl "set-x11-keymap" (str/join "," layouts))
  (keyboard-layouts))


(defn reboot! []
  (sudo :systemctl "reboot"))


(defn shutdown! []
  (sudo :systemctl "poweroff")) 


(defn volume []
  (let [out (:out (sh :pactl "get-sink-volume" "@DEFAULT_SINK@"))]
    (Integer/parseInt (second (re-find #"(\d+)%" out)))))


(defn volume! [value]
    (let [value (-> value int (max 0) (min 100))]
      (sh :pactl "set-sink-volume" "@DEFAULT_SINK@" (str value "%"))
      (volume)))
