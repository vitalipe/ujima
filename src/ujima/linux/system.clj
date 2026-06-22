(ns ujima.linux.system
  (:require [clojure.string :as str]
            [lib.shell :refer [$?]]
            [ujima.linux.sudo :refer [sudo$?]]))


(defn hostname []
  (:out ($? hostnamectl --static)))


(defn hostname! [hostname]
  (sudo$? hostnamectl set-hostname [hostname])
  (:out ($? hostnamectl --static)))


(defn timezone []
  (:out ($? timedatectl show -p "Timezone" --value)))


(defn timezone! [timezone]
  (sudo$? timedatectl set-timezone [timezone])
  (:out ($? timedatectl show -p "Timezone" --value)))


(defn keyboard-layouts []
  []) ;; TODO


(defn keyboard-layouts! [layouts]
  (sudo$? localectl set-x11-keymap [(str/join "," layouts)])
  (keyboard-layouts))


(defn reboot! []
  (sudo$? systemctl reboot))


(defn shutdown! []
  (sudo$? systemctl poweroff))
