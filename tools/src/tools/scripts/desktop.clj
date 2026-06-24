(ns tools.scripts.desktop
  "Runs INSIDE the target chroot as root (and is the live `dev push desktop` deploy path). Stages
   the ujima *desktop* layer — its config files + assets — onto the base.

   SCAFFOLD: the desktop layer doesn't exist yet. This stages assets/desktop into /opt/ujima/desktop
   when that dir is present, and is otherwise a no-op. The graphical session's systemd unit will
   live in tools.scripts.ujimaify; runtime desktop *settings* (wallpaper, resolution, …) are the
   agent's job at runtime, not this build script.

   Pipeline: install -> base -> agent -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]))


(defn run! [{:keys [project]}]
  (with-console-out
    (let [src (str project "/assets/desktop")]
      (if (fs/exists? src)
        (do                                   ;; clean-mirror, preserving the exec bit (cp -a)
          (fs/create-dirs "/opt/ujima")
          ($! rm -rf "/opt/ujima/desktop")
          ($! cp -a [src] "/opt/ujima/desktop"))
        (println "desktop: no assets/desktop yet — scaffold no-op")))))
