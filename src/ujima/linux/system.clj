(ns ujima.linux.system
  (:require [lib.shell :refer [$?]]
            [ujima.linux.sudo :refer [sudo$?]]))


(defn hostname []
  (:out ($? hostnamectl --static)))


(defn hostname! [hostname]
  (sudo$? hostnamectl set-hostname [hostname])
  ;; hostnamectl never touches /etc/hosts; base.clj seeds the 127.0.1.1 line in every image
  (sudo$? sed -i [(str "s/^127.0.1.1.*/127.0.1.1\\t" hostname "/")] "/etc/hosts")
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
