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

    ;; suppress in-chroot initramfs generation: overlayroot's postinst trigger runs update-initramfs,
    ;; which SEGFAULTS under qemu. We bake a prebuilt, kernel-matched initramfs instead
    ;; (tools.cmd.image/initramfs!, from stage), so the chroot never generates one. `=no` also fits
    ;; the immutable model (the initramfs is fixed; a kernel bump is a rebuild, not in-place regen).
    (spit "/etc/initramfs-tools/update-initramfs.conf" "update_initramfs=no\nbackup_initramfs=no\n")

    ;; overlayroot: the read-only-root tmpfs overlay mechanism (lower=ro-root + upper=tmpfs at boot,
    ;; via the initramfs hook baked above). Installed into every image (the cached vendor base);
    ;; activated via the `overlayroot=tmpfs:recurse=0` cmdline token (autoboot.bootfiles/cmdline!),
    ;; and toggled off on dev with assets/dev/lock-fs.
    ($! apt-get install -y --no-install-recommends "overlayroot")

    ;; runtime babashka: the same vendored aarch64 binary we are running under,
    ;; copied + made executable in one shot
    ($! install -m "0755"
                (str project "/assets/tools/bb-aarch64")
                "/usr/local/bin/bb")))
