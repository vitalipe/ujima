(ns tools.scripts.ujimaify
  "Runs INSIDE the target chroot as root. 'ujimaifies' the staged system: writes + enables the
   systemd units that turn the staged agent (and, later, desktop) into boot services, and stamps
   the build. Writing/enabling units lives here (rare); *restarting* them for a live iteration is
   the CLI's job (`dev push`), not this script. (fstab is written per-slot at install time —
   ujima.device.ab/install-into-slot! — not here.)

   Pipeline: install -> base -> agent -> desktop -> ujimaify -> [dev] -> [cleanup]."
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]))


;; The graphical session is the boot service now — NOT the agent. The agent moved INTO the X
;; session (it owns desktop lifecycle: i3 IPC, eww, app launch, audio — all session-scoped), so it
;; runs as `ujima` via i3 `exec` (see /opt/ujima/desktop/i3/config) through the `ujima-agent`
;; wrapper below. `ujima.service` is repurposed to run + SUPERVISE the session: systemd runs startx
;; on tty1 as ujima, and Restart=always brings the whole desktop back if i3/X dies — that IS the
;; session-recovery a standalone watchdog would otherwise provide. Conflicts=getty@tty1 hands tty1
;; over from the base console autologin; PAMName=login sets up the logind session (XDG_RUNTIME_DIR).
;; HW-VERIFIED: do NOT set TTYPath/StandardInput=tty — systemd grabbing tty1 collides with X's own
;; VT management and the service flaps; without it startx opens vt1 itself and the session is stable.
(def ^:private ujima-service
  (str "[Unit]\n"
       "Description=ujima desktop session\n"
       "After=systemd-user-sessions.service\n"
       "Conflicts=getty@tty1.service\n"
       "\n"
       "[Service]\n"
       "User=ujima\n"
       "PAMName=login\n"
       "StandardOutput=journal\n"
       "Restart=always\n"
       "RestartSec=3\n"
       "ExecStart=/usr/bin/startx -- vt1\n"
       "\n"
       "[Install]\n"
       "WantedBy=multi-user.target\n"))


;; The in-session agent launcher. i3 execs `ujima-agent` as the session user, so app/window
;; lifecycle + the loopback API run with the session env (DISPLAY, XDG_RUNTIME_DIR) — exactly what
;; they need and what a root boot service couldn't give. Privileged ops still go through `sudo$`
;; (ujima has NOPASSWD), so running as the user costs nothing.
(def ^:private ujima-agent-wrapper
  (str "#!/bin/sh\n"
       "cd /opt/ujima\n"
       "exec /usr/local/bin/bb -cp src -m ujima.core\n"))


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
    ;; the desktop session service (write + enable; supervises startx→i3 with Restart=always)
    (spit "/etc/systemd/system/ujima.service" ujima-service)
    ($! systemctl enable "ujima")

    ;; the in-session agent launcher (i3 execs this) + the staged xinitrc that startx runs
    (spit "/usr/local/bin/ujima-agent" ujima-agent-wrapper)
    ($! chmod "0755" ["/usr/local/bin/ujima-agent"])
    (fs/create-dirs "/etc/X11/xinit")
    ($! cp "/opt/ujima/desktop/xinitrc" "/etc/X11/xinit/xinitrc")

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
