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


;; Overlay-safe machine-id. Under the overlay (every image, see cmdline!) systemd can't persist
;; /etc/machine-id — writes hit the ephemeral tmpfs upper, so PID 1 mints a random id each boot,
;; churning journald's /var/log/journal/<machine-id>/ into one orphan dir per boot (defeating the
;; persistent journal above). Fix: an initramfs hook derives the id from the board serial and writes
;; /etc/machine-id before PID 1 reads it — re-derived identically each boot, so it's stable from the
;; FIRST boot (no orphan, no reboot to settle). PREREQ=overlayroot → runs after the overlay mounts,
;; so ${rootmnt} is the overlay and the write lands in its upper. The producer
;; (assets/dev/build-initramfs) bakes it into the stash and image initramfs ships that, so this just
;; needs to be present in the rootfs for the producer's update-initramfs to pick up.
(def ^:private machine-id-hook
  (str "#!/bin/sh\n"
       "PREREQ=\"overlayroot\"\n"
       "prereqs() { echo \"$PREREQ\"; }\n"
       "case \"$1\" in prereqs) prereqs; exit 0;; esac\n"
       "serial=$(tr -dc 'a-zA-Z0-9' < /sys/firmware/devicetree/base/serial-number 2>/dev/null)\n"
       "[ -n \"$serial\" ] || exit 0\n"
       "mid=$(printf '%s' \"$serial\" | sha256sum | cut -c1-32)\n"
       "printf '%s\\n' \"$mid\" > \"${rootmnt}/etc/machine-id\"\n"))


(defn run! [_opts]
  (with-console-out
    ;; agent boot service (write + enable; restart-on-iterate is the CLI's job, not here)
    (spit "/etc/systemd/system/ujima.service" ujima-service)
    ($! systemctl enable "ujima")

    ;; persistent capped journal on storage
    (fs/create-dirs "/etc/systemd/journald.conf.d")
    (spit "/etc/systemd/journald.conf.d/ujima.conf" journald-conf)

    ;; overlay-safe machine-id: initramfs hook (the producer bakes it; image initramfs ships it)
    (let [hook "/etc/initramfs-tools/scripts/init-bottom/ujima-machine-id"]
      (fs/create-dirs (fs/parent hook))
      (spit hook machine-id-hook)
      ($! chmod "0755" [hook]))

    ;; overlayfs rejects `mount -o remount` via the modern mount API ("No changes allowed in
    ;; reconfigure"), so systemd-remount-fs — which remounts / rw early in boot — fails under the
    ;; overlay root. The overlay already gives us a writable / (the tmpfs upper), so the service is
    ;; redundant: mask it. (overlayroot already comments out the fstab / line; this is the rest.)
    ($! ln -sf "/dev/null" "/etc/systemd/system/systemd-remount-fs.service")

    ;; build marker
    (spit "/etc/ujima-release"
          (str "built-at=" (java.time.Instant/now) "\n"))))
