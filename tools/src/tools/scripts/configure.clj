(ns tools.scripts.configure
  "Runs INSIDE the target chroot as root. Places the ujima agent source + deployment
   config onto the image.

   `project` is the read-only repo bind inside the chroot (default /ujima-src).

   TODO (need artifacts/decisions that don't exist in the repo yet):
     - systemd unit to launch ujima.core/-main on boot
     - /etc/fstab for the A/B + config + storage partitions
     - hardening pass"
  (:require [babashka.process :refer [shell]]
            [babashka.fs :as fs]))


(defn run! [{:keys [project]}]
  (let [dst "/opt/ujima"]
    (fs/create-dirs (str dst "/config"))

    ;; agent source + deployment config
    (shell "cp" "-a" (str project "/src")             (str dst "/"))
    (shell "cp" "-a" (str project "/config/ujima.edn") (str dst "/config/"))

    ;; release marker
    (spit "/etc/ujima-release"
          (str "built-at=" (java.time.Instant/now) "\n"))))
