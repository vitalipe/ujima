(ns tools.scripts.ujimad
  "Runs INSIDE the target chroot as root (and is the live `dev push ujimad` deploy path). Stages
   ujimad: its source tree + deployment config into /ujima/ujimad. This is the artifact you
   iterate on most — re-run it (then restart ujimad) to pick up code changes. The systemd unit
   that runs it lives in tools.scripts.ujimaify.

   Pipeline: install -> base -> ujimad -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]))


(defn run! [{:keys [project]}]
  (with-console-out
    (let [dst "/ujima/ujimad"]
      (fs/create-dirs (str dst "/config"))

      ;; clean-mirror src ONLY — config is copied, never rm'd, so a hand-dropped
      ;; ujimad.local.edn survives pushes
      ($! rm -rf (str dst "/src"))
      ($! cp -a (str project "/src")              (str dst "/"))
      ($! cp -a (str project "/config/ujimad.edn") (str dst "/config/")))))
