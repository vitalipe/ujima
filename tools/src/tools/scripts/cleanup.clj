(ns tools.scripts.cleanup
  "Runs INSIDE the target chroot as root. Image hygiene so every flashed card ships
   clean and gets a fresh identity on first boot."
  (:require [babashka.process :refer [shell]]
            [babashka.fs :as fs]))


(defn run! [_]
  ;; apt caches
  (shell "apt-get" "clean")
  (shell "sh" "-c" "rm -rf /var/lib/apt/lists/*")

  ;; logs
  (shell "sh" "-c" "find /var/log -type f -exec truncate -s 0 {} +")

  ;; machine identity — regenerated on first boot
  (spit "/etc/machine-id" "")
  (fs/delete-if-exists "/var/lib/dbus/machine-id")
  (shell "sh" "-c" "rm -f /etc/ssh/ssh_host_*")

  ;; shell history
  (shell "sh" "-c" "rm -f /root/.bash_history /home/*/.bash_history"))
