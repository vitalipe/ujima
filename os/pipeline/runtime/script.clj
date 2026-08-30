(ns pipeline.runtime.script
  "Runs INSIDE the target chroot as root (and is the live `dev push runtime` deploy path).
   Stages the ujima core codebase — the runtime/ source tree + deployment config — into
   /ujima/ujimad, and installs its two entry points (the ujimad launcher, the ujimactl
   wrapper): the code and its front doors are one unit, deployed together. runtime/ is
   shared, not the daemon's own tree: tools + the os build link it on the host, and
   on-device consumers beyond ujimad (the installer) run from this same deploy. This is
   the artifact you iterate on most — re-run it (then restart ujimad) to pick up code
   changes. The systemd unit that runs it lives in the ujimaify stage.

   Pipeline: install -> boot -> base -> runtime -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]
            [build.files :as files]
            [build.schema :as schema]))


(defn run! [{:keys [project]}]
  (with-console-out
    (let [dst "/ujima/ujimad"]
      (fs/create-dirs (str dst "/config"))

      ;; clean-mirror src ONLY — config is copied, never rm'd, so a hand-dropped
      ;; ujimad.local.edn survives pushes
      ($! rm -rf (str dst "/src"))
      ($! cp -a (str project "/runtime/src")              (str dst "/"))
      ($! cp -a (str project "/runtime/config/ujimad.edn") (str dst "/config/"))

      ;; the entry points over this layout, deployed with the code they launch
      (files/install! project "runtime/bin/ujimad"   "/usr/local/bin/ujimad")
      (files/install! project "runtime/bin/ujimactl" "/usr/local/bin/ujimactl")

      ;; the catalogs gate: this rootfs must match the pinned tz/xkb lists exactly
      (println "catalogs" (schema/verify! "/"))

      ;; the image's identity -> ujimad's env base layer; last, so only a completed
      ;; deploy stamps
      (when-not (fs/exists? "/ujima/image.edn")
        (throw (ex-info "no /ujima/image.edn — not a stamped image" {})))
      ($! cp "/ujima/image.edn" (str dst "/config/env.edn"))
      (println "env" (slurp "/ujima/image.edn")))))
