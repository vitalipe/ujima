(ns pipeline.ujimad.script
  "Runs INSIDE the target chroot as root (and is the live `dev push ujimad` deploy path). Stages
   the ujima core codebase — the runtime/ source tree + deployment config — into
   /ujima/ujimad. runtime/ is shared, not the daemon's own tree: tools + the os build link
   it on the host, and on-device consumers beyond ujimad (the installer) will run from this
   same deploy. This is the artifact you iterate on most — re-run it (then restart ujimad)
   to pick up code changes. The systemd unit that runs it lives in the ujimaify stage.

   Pipeline: install -> boot -> base -> ujimad -> desktop -> ujimaify -> [dev] -> [cleanup].

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
      ($! cp -a (str project "/runtime/src")              (str dst "/"))
      ($! cp -a (str project "/runtime/config/ujimad.edn") (str dst "/config/")))))
