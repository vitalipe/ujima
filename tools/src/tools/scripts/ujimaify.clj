(ns tools.scripts.ujimaify
  "Runs INSIDE the target chroot as root. 'ujimaifies' the staged system: writes + enables the
   systemd units that turn the staged agent (and, later, desktop) into boot services, and stamps
   the build. Writing/enabling units lives here (rare); *restarting* them for a live iteration is
   the CLI's job (`dev push`), not this script. (fstab is written per-slot at install time —
   ujima.device.ab/install-into-slot! — not here.)

   Pipeline: install -> base -> agent -> desktop -> ujimaify -> [dev] -> [cleanup]."
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]))


;; The agent's boot service. Root: the agent reconciles system settings (hostnamectl, localectl,
;; …) that need privilege. /usr/local/bin/bb is installed by tools.scripts.install; src + config
;; are staged by tools.scripts.agent. Restart=on-failure so a crash retries without hammering.
(def ^:private ujima-service
  (str "[Unit]\n"
       "Description=ujima agent\n"
       "After=network-online.target\n"
       "Wants=network-online.target\n"
       "\n"
       "[Service]\n"
       "Type=simple\n"
       "WorkingDirectory=/opt/ujima\n"
       "ExecStart=/usr/local/bin/bb -cp src -m ujima.core\n"
       "Restart=on-failure\n"
       "RestartSec=2\n"
       "\n"
       "[Install]\n"
       "WantedBy=multi-user.target\n"))


;; Persistent, capped journal — backed by the storage partition via slot->fstab's /var/log/journal
;; bind. Storage=persistent is explicit: `auto` can stay volatile even with the dir present.
(def ^:private journald-conf
  (str "[Journal]\n"
       "Storage=persistent\n"
       "SystemMaxUse=256M\n"))


;; Overlay-safe machine-id. The overlay is on for every image (see cmdline!), and under it systemd
;; can't persist /etc/machine-id — the write lands in the ephemeral tmpfs upper, so PID 1 mints a
;; fresh random id every boot, which would churn journald's /var/log/journal/<machine-id>/ into one
;; orphan dir per boot (defeating the persistent journal above). Fix: pin the id to a hash of the
;; board serial by appending systemd.machine_id= to cmdline.txt (the boot partition lives OUTSIDE
;; the overlay), so PID 1 reads the same id every boot -> one stable journal dir. Idempotent (the
;; grep guard); also fine on a lock-fs-disabled (rw) dev box. Cost: the very first boot still uses a
;; random id (one tiny orphan) until the next reboot picks up the param.
(def ^:private machine-id-script
  (str "#!/bin/sh\n"
       "set -eu\n"
       "cmdline=/boot/firmware/cmdline.txt\n"
       "grep -q 'systemd.machine_id=' \"$cmdline\" && exit 0\n"
       "serial=$(tr -dc 'a-zA-Z0-9' < /proc/device-tree/serial-number)\n"
       "mid=$(printf '%s' \"$serial\" | sha256sum | cut -c1-32)\n"
       "sed -i '1 s/$/ systemd.machine_id='\"$mid\"'/' \"$cmdline\"\n"))


(def ^:private machine-id-unit
  (str "[Unit]\n"
       "Description=Pin machine-id to a hash of the board serial (overlay-safe)\n"
       "ConditionPathExists=/proc/device-tree/serial-number\n"
       "ConditionPathExists=/boot/firmware/cmdline.txt\n"
       "\n"
       "[Service]\n"
       "Type=oneshot\n"
       "ExecStart=/usr/local/sbin/ujima-machine-id\n"
       "RemainAfterExit=yes\n"
       "\n"
       "[Install]\n"
       "WantedBy=multi-user.target\n"))


;; First-boot self-heal for the overlay. Our cross-build runs update-initramfs under qemu, where it
;; segfaults — so the flashed initramfs lacks the overlayroot hook and the cmdline token does nothing
;; (the Pi boots rw). On real hardware, regenerate it natively (once) and reboot so the overlay
;; engages. Keys off the OUTCOME (cmdline wants the overlay, but / is not an overlay mount), so it also
;; no-ops under `lock-fs disable` and would re-heal a future kernel update. Loop-safe via a marker on
;; /boot/firmware (persistent, outside the overlay): regen at most once, else fail loudly to the
;; journal rather than reboot-loop. Ordered after the machine-id pin so the one self-heal reboot also
;; lands the stable machine-id.
(def ^:private overlay-init-script
  (str "#!/bin/sh\n"
       "set -eu\n"
       "marker=/boot/firmware/.ujima-initramfs-fixed\n"
       "[ \"$(findmnt -no FSTYPE /)\" = overlay ] && { rm -f \"$marker\"; exit 0; }\n"
       "grep -q 'overlayroot=tmpfs' /proc/cmdline || exit 0\n"
       "if [ -e \"$marker\" ]; then\n"
       "  echo 'ujima-overlay-init: overlay still inactive after a regen attempt; not rebooting' >&2\n"
       "  exit 1\n"
       "fi\n"
       ;; mark only AFTER a successful regen — a FAILED regen (e.g. the first boot if / came up
       ;; read-only) must NOT leave a marker, or every later boot gives up and never retries.
       "update-initramfs -u -k all\n"
       "touch \"$marker\"\n"
       "systemctl reboot\n"))


(def ^:private overlay-init-unit
  (str "[Unit]\n"
       "Description=Self-heal the overlayroot initramfs hook (cross-build/qemu gap), then reboot\n"
       "After=ujima-machine-id.service\n"
       "\n"
       "[Service]\n"
       "Type=oneshot\n"
       "ExecStart=/usr/local/sbin/ujima-overlay-init\n"
       "RemainAfterExit=yes\n"
       "\n"
       "[Install]\n"
       "WantedBy=multi-user.target\n"))


(defn run! [_opts]
  (with-console-out
    ;; agent boot service (write + enable; restart-on-iterate is the CLI's job, not here)
    (spit "/etc/systemd/system/ujima.service" ujima-service)
    ($! systemctl enable "ujima")

    ;; persistent capped journal on storage
    (fs/create-dirs "/etc/systemd/journald.conf.d")
    (spit "/etc/systemd/journald.conf.d/ujima.conf" journald-conf)

    ;; overlay-safe machine-id: oneshot that pins systemd.machine_id from the board serial
    (let [bin "/usr/local/sbin/ujima-machine-id"]
      (spit bin machine-id-script)
      ($! chmod "0755" [bin]))
    (spit "/etc/systemd/system/ujima-machine-id.service" machine-id-unit)
    ($! systemctl enable "ujima-machine-id")

    ;; first-boot self-heal: natively regenerate the overlayroot initramfs hook (qemu can't), reboot
    (let [bin "/usr/local/sbin/ujima-overlay-init"]
      (spit bin overlay-init-script)
      ($! chmod "0755" [bin]))
    (spit "/etc/systemd/system/ujima-overlay-init.service" overlay-init-unit)
    ($! systemctl enable "ujima-overlay-init")

    ;; overlayfs rejects `mount -o remount` via the modern mount API ("No changes allowed in
    ;; reconfigure"), so systemd-remount-fs — which remounts / rw early in boot — fails under the
    ;; overlay root. The overlay already gives us a writable / (the tmpfs upper), so the service is
    ;; redundant: mask it. (overlayroot already comments out the fstab / line; this is the rest.)
    ($! ln -sf "/dev/null" "/etc/systemd/system/systemd-remount-fs.service")

    ;; build marker
    (spit "/etc/ujima-release"
          (str "built-at=" (java.time.Instant/now) "\n"))))
