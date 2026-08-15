(ns ujima.linux.system
  (:require [clojure.string :as str]
            [lib.io :refer [slurp-text]]
            [lib.shell :refer [$? $!]]
            [ujima.linux.sudo :refer [sudo$! sudo$?]]))


(defn hostname []
  (:out ($? hostnamectl --static)))


(defn hostname! [hostname]
  (sudo$! hostnamectl set-hostname [hostname])
  ;; hostnamectl never touches /etc/hosts; base.clj seeds the 127.0.1.1 line in every image
  (sudo$! sed -i (str "s/^127.0.1.1.*/127.0.1.1\\t" hostname "/") "/etc/hosts")
  ($! hostnamectl --static))


(defn timezone []
  (:out ($? timedatectl show -p "Timezone" --value)))


(defn timezone! [timezone]
  (sudo$! timedatectl set-timezone [timezone])
  ($! timedatectl show -p "Timezone" --value))


(defn uptime-minutes []
  (some-> (slurp-text "/proc/uptime" nil) (str/split #"\s") first parse-double (/ 60) long))


(defn clock!
  "Set the wall clock to EPOCH-MS; the RTC write is best-effort (no RTC, no error)."
  [epoch-ms]
  (sudo$! date -u -s [(str "@" (quot epoch-ms 1000))])
  (sudo$? hwclock -w))


(defn reboot! []
  (sudo$! systemctl reboot))


(defn shutdown! []
  (sudo$! systemctl poweroff))
