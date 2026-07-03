(ns ujima.linux.system
  (:require [lib.shell :refer [$?]]
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


(defn reboot! []
  (sudo$? systemctl reboot))


(defn shutdown! []
  (sudo$? systemctl poweroff))
