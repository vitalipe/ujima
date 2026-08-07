(ns cleanup.script
  "Runs INSIDE the target chroot as root. Image hygiene so every flashed card ships
   clean and gets a fresh identity on first boot."
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]))


(defn run! [_]
  (with-console-out
    ;; apt caches
    ($! apt-get clean)
    ($! sh -c "rm -rf /var/lib/apt/lists/*")

    ;; logs
    ($! sh -c "find /var/log -type f -exec truncate -s 0 {} +")

    ;; machine identity — regenerated on first boot
    (spit "/etc/machine-id" "")
    (fs/delete-if-exists "/var/lib/dbus/machine-id")
    ($! sh -c "rm -f /etc/ssh/ssh_host_*")

    ;; shell history
    ($! sh -c "rm -f /root/.bash_history /home/*/.bash_history")))
