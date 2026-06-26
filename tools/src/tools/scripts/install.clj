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

    ;; overlayroot: the read-only-root tmpfs overlay mechanism. Its postinst wires the initramfs
    ;; hook that builds lower=ro-root + upper=tmpfs at boot. Installed into every image (this is the
    ;; cached vendor base); activated on every image via the `overlayroot=tmpfs` cmdline token
    ;; (ujima.device.ab.autoboot.bootfiles/cmdline!), and toggled off on dev with assets/dev/lock-fs.
    ($! apt-get install -y --no-install-recommends "overlayroot")

    ;; runtime babashka: the same vendored aarch64 binary we are running under,
    ;; copied + made executable in one shot
    ($! install -m "0755"
                (str project "/assets/tools/bb-aarch64")
                "/usr/local/bin/bb")))
