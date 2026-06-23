(ns tools.scripts.stripdown
  "Runs INSIDE the target chroot as root. Strips the stock raspios first-boot machinery and
   points the base image at the ujima A/B layout, so it boots cleanly instead of dead-ending
   in emergency mode or a setup wizard.

   Produces a clean, bootable ujima *base*; tools.scripts.configure then builds the ujima
   desktop on top. Pipeline: stage -> stripdown -> configure -> pack -> from-pack.

   `project` (the read-only repo bind) is unused here.

   TODO:
     - per-device identity: empty /etc/machine-id + remove ssh host keys (the firstboot
       wizard is masked below, so emptying machine-id is then safe)
     - emit the /boot/firmware (and root) fstab entries per A/B slot at install time"
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]))


;; The build stamps an MBR disk-id of 0x00C0FFEE (ujima.device.ab.autoboot.partitions), so the
;; partitions are reachable as PARTUUID=00c0ffee-NN. Slot A's boot partition is #2.
(def ^:private fstab-contents
  ;; Replaces the BASE image's /etc/fstab, which points at the base image's own PARTUUIDs
  ;; (a different disk-id). On the ujima disk those devices don't exist, so /boot/firmware
  ;; times out -> local-fs.target fails -> emergency mode.
  ;;   - no '/' entry: the kernel mounts root from the cmdline (root=PARTUUID, per slot) +
  ;;     'rw' (see ujima.device.ab.autoboot.bootfiles/cmdline!). A hardcoded '/' line would
  ;;     be wrong for the other slot.
  ;;   - /boot/firmware is slot A's boot partition (00c0ffee-02) + nofail. from-pack installs
  ;;     slot A; TODO: emit this per-slot at install time for true A/B.
  (str "proc                  /proc           proc  defaults         0  0\n"
       "PARTUUID=00c0ffee-02  /boot/firmware  vfat  defaults,nofail  0  2\n"))


(defn- mask!
  "systemd 'mask': symlink the unit to /dev/null so it can never start, even if something
   still 'wants' it."
  [unit]
  ($! ln -sf "/dev/null" (str "/etc/systemd/system/" unit)))


(defn run! [_opts]
  (with-console-out
    ;; 1. fstab pointing at the ujima partitions (not the base image's dead PARTUUIDs)
    (spit "/etc/fstab" fstab-contents)

    ;; 2. disable cloud-init: with no datasource it stalls on first boot and never finishes.
    ;;    This marker file is cloud-init's documented kill-switch. (configure then creates the
    ;;    login user that cloud-init would otherwise have provisioned.)
    (when (fs/exists? "/etc/cloud")
      (spit "/etc/cloud/cloud-init.disabled" ""))

    ;; 3. mask raspios first-boot units that assume the stock 2-partition layout:
    ;;    - root-growers: would expand '/' to fill the card and can clobber the adjacent slot
    ;;    - setup wizards: grab a tty and block the normal login prompt
    (doseq [unit ["rpi-resize.service"            ;; "Grow and trim root filesystem on first boot"
                  "systemd-growfs-root.service"   ;; "Grow Root File System"
                  "userconfig.service"            ;; raspios "User configuration dialog"
                  "systemd-firstboot.service"]]   ;; systemd "First Boot Wizard"
      (mask! unit))))
