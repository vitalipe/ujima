(ns tools.scripts.install
  "Runs INSIDE the target chroot (aarch64 bb under qemu) as root.
   Installs runtime packages and the runtime babashka.

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! with-console-out]]))


(defn run! [{:keys [project]}]
  (with-console-out
    ;; base packages the agent needs at runtime
    ($! apt-get update)
    ($! apt-get install -y --no-install-recommends "ca-certificates")

    ;; runtime babashka: the same vendored aarch64 binary we are running under,
    ;; copied + made executable in one shot
    ($! install -m "0755"
                (str project "/assets/tools/bb-aarch64")
                "/usr/local/bin/bb")))
