(ns tools.scripts.agent
  "Runs INSIDE the target chroot as root (and is the live `dev push agent` deploy path). Stages
   the ujima agent: its source tree + deployment config into /opt/ujima. This is the artifact you
   iterate on most — re-run it (then restart the agent) to pick up code changes. The systemd unit
   that runs it lives in tools.scripts.ujimaify.

   Pipeline: install -> base -> agent -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]))


(defn run! [{:keys [project]}]
  (with-console-out
    (let [dst "/opt/ujima"]
      (fs/create-dirs (str dst "/config"))

      ;; agent source + deployment config. Clean-mirror src (rm before cp) so a file deleted in
      ;; the working tree doesn't linger on a re-run — matches the desktop script. Build-safe:
      ;; a fresh slot has no /opt/ujima/src, so the rm is a no-op there.
      ($! rm -rf (str dst "/src"))
      ($! cp -a (str project "/src")              (str dst "/"))
      ($! cp -a (str project "/config/ujima.edn") (str dst "/config/")))))
