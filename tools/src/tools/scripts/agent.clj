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

      ;; agent source + deployment config
      ($! cp -a (str project "/src")              (str dst "/"))
      ($! cp -a (str project "/config/ujima.edn") (str dst "/config/")))))
