(ns tools.scripts.base
  "Runs INSIDE the target chroot as root. Turns a stock raspios rootfs into the ujima *base*:
   strips the first-boot machinery, disables cloud-init, and provisions the login user
   (passwordless console autologin + passwordless sudo) — a clean, bootable, logged-in machine
   that the agent/desktop layers build on. (fstab + boot units → tools.scripts.ujimaify.)

   Pipeline: install -> base -> agent -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` (the read-only repo bind) is unused here."
  (:require [lib.shell :refer [$ $! $? with-console-out]]
            [babashka.fs :as fs]))


(defn- mask!
  "systemd 'mask': symlink the unit to /dev/null so it can never start, even if something
   still 'wants' it."
  [unit]
  ($! ln -sf "/dev/null" (str "/etc/systemd/system/" unit)))


(defn- create-login-user!
  "cloud-init (disabled below) would have created the login user from /boot/firmware/user-data,
   so create one here. The default ujima/ujima credential is intentional: ujima is a public-access
   machine where physical access already implies root, so the login password isn't a secret
   (drive it from config if that ever changes)."
  []
  (when-not (:ok? ($? id "ujima"))
    ;; video+render: GPU/DRM access so X (and eww/chromium) can use the display, not just software.
    ;; audio: /dev/snd for the session PipeWire — the session is seatless (no logind ACLs)
    ($! useradd -m -s "/bin/bash" -G "sudo,video,render,audio" "ujima"))
  (-> ($ echo "ujima:ujima") ($! chpasswd)))


(defn- grant-passwordless-sudo!
  "Let ujima run sudo without a password prompt. Dropped into /etc/sudoers.d (sudo @includedir's
   the dir); the file MUST be 0440 + root-owned or sudo silently ignores it. `visudo -c` fails
   the build loudly on a malformed rule rather than shipping an image whose sudo is broken."
  []
  (let [f "/etc/sudoers.d/ujima-nopasswd"]
    (spit f "ujima ALL=(ALL) NOPASSWD: ALL\n")
    ($! chmod "0440" [f])
    ($! visudo -c -f [f])))


(defn- enable-console-autologin!
  "Log ujima straight into a tty1 console on boot — no password prompt. This is the Lite/console
   form of 'logged in'; the eventual desktop session supersedes it. Canonical systemd drop-in
   (the same one raspi-config writes): the empty ExecStart clears the unit's inherited command,
   then agetty --autologin replaces it. Nothing ships an autologin drop-in on the stripped base
   (cloud-init + userconfig are off), so this is the only one."
  []
  (let [dir "/etc/systemd/system/getty@tty1.service.d"]
    (fs/create-dirs dir)
    (spit (str dir "/autologin.conf")
          (str "[Service]\n"
               "ExecStart=\n"
               "ExecStart=-/sbin/agetty --autologin ujima --noclear %I $TERM\n"))))


(defn run! [_opts]
  (with-console-out
    ;; 1. disable cloud-init: with no datasource it stalls on first boot and never finishes.
    ;;    This marker file is cloud-init's documented kill-switch. (We create the login user it
    ;;    would otherwise have provisioned, below.)
    (when (fs/exists? "/etc/cloud")
      (spit "/etc/cloud/cloud-init.disabled" ""))

    ;; 2. mask raspios first-boot units that assume the stock 2-partition layout:
    ;;    - root-growers: would expand '/' to fill the card and can clobber the adjacent slot
    ;;    - setup wizards: grab a tty and block the normal login prompt
    (doseq [unit ["rpi-resize.service"            ;; "Grow and trim root filesystem on first boot"
                  "systemd-growfs-root.service"   ;; "Grow Root File System"
                  "userconfig.service"            ;; raspios "User configuration dialog"
                  "systemd-firstboot.service"]]   ;; systemd "First Boot Wizard"
      (mask! unit))

    ;; 3. login user (cloud-init is off, so nothing else makes one)
    (create-login-user!)
    (grant-passwordless-sudo!)
    (enable-console-autologin!)

    ;; 4. map the hostname so sudo/X stop warning "unable to resolve host ujima"
    ($! sh -c "grep -qw ujima /etc/hosts || printf '127.0.1.1\\tujima\\n' >> /etc/hosts")

    ;; 5. A/B disk mount points + bind targets — rootfs layout is build content, so every
    ;;    image carries its own. The per-slot fstab that references them is written at
    ;;    install time (ujima.device.ab.autoboot/slot->fstab).
    (doseq [dir ["/mnt/settings"   "/mnt/storage"
                 "/ujima/settings" "/ujima/storage"
                 "/var/log/journal"]]
      (fs/create-dirs dir))))
