(ns tools.scripts.install
  "Runs INSIDE the target chroot (aarch64 bb under qemu) as root.
   Installs runtime packages and the runtime babashka.

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [babashka.process :refer [shell]]
            [babashka.fs :as fs]))


(defn run! [{:keys [project]}]
  ;; base packages the agent needs at runtime
  (shell "apt-get" "update")
  (shell "apt-get" "install" "-y" "--no-install-recommends"
         "ca-certificates")

  ;; runtime babashka: the same vendored aarch64 binary we are running under
  (let [bb "/usr/local/bin/bb"]
    (fs/copy (str project "/assets/tools/bb-aarch64") bb {:replace-existing true})
    (shell "chmod" "+x" bb)))
