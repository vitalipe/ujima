(ns ujima.linux.system
  (:require [clojure.string :as str]
            [lib.io :refer [slurp-text]]
            [lib.shell :refer [$? $!]]
            [ujima.linux.sudo :refer [sudo$! sudo$?]]))



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
