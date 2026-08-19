(ns pipeline.base.script
  "Runs INSIDE the target chroot as root. Turns a stock raspios rootfs into the ujima *base*:
   strips the first-boot machinery, disables cloud-init, and provisions the login user
   (passwordless console autologin + passwordless sudo) — a clean, bootable, logged-in machine
   that the ujimad/desktop layers build on. Static files live in os/pipeline/base/<concern>/ (login,
   identity, x11, network); this script is the pulls + the actions. (fstab + boot units → the ujimaify stage.)

   Pipeline: install -> boot -> base -> ujimad -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$ $! $? with-console-out]]
            [babashka.fs :as fs]
            [build.files :as files]))


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


(defn run! [{:keys [project]}]
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

    ;; 3. login user (cloud-init is off, so nothing else makes one) + passwordless sudo
    ;;    (0440 or sudo silently ignores it; visudo -c fails the build loudly on a bad
    ;;    rule) + tty1 console autologin
    (create-login-user!)
    (files/install! project "base/login/ujima-nopasswd" "/etc/sudoers.d/ujima-nopasswd"
                    {:mode "0440"})
    ($! visudo -c -f "/etc/sudoers.d/ujima-nopasswd")
    (files/install! project "base/login/autologin.conf"
                    "/etc/systemd/system/getty@tty1.service.d/autologin.conf")

    ;; 4. default host identity — the baked name IS the default ([:system :hostname] is nil
    ;;    unless a rename is set); the hosts mapping keeps sudo/X from warning "unable to
    ;;    resolve"; the initramfs hook keeps machine-id stable under the overlay (the why
    ;;    lives in the file)
    (files/install! project "base/identity/hostname" "/etc/hostname")
    ($! sh -c "grep -qw ujimaos /etc/hosts || printf '127.0.1.1\\tujimaos\\n' >> /etc/hosts")
    (files/install! project "base/identity/ujima-machine-id"
                    "/etc/initramfs-tools/scripts/init-bottom/ujima-machine-id")

    ;; 5. X bring-up plumbing — the packages ride install's cached layer; the confs live
    ;;    HERE so an edit ships without a package-set (cache-key) change
    (files/install! project "base/x11/Xwrapper.config" "/etc/X11/Xwrapper.config")
    (files/install! project "base/x11/99-vc4.conf" "/etc/X11/xorg.conf.d/99-vc4.conf")

    ;; 6. wifi powersave off for every connection — dozing costs seconds-scale
    ;;    latency outliers for ≤~0.2W on mains-powered machines (numbers in the conf)
    (files/install! project "base/network/wifi-powersave.conf"
                    "/etc/NetworkManager/conf.d/ujima-wifi-powersave.conf")

    ;; 7. A/B disk mount points + bind targets — rootfs layout is build content, so every
    ;;    image carries its own. The per-slot fstab that references them is written at
    ;;    install time (ujima.device.ab.autoboot/slot->fstab). /mnt/settings is the one
    ;;    path outside /ujima; never target the /ujima root itself — only named children.
    (doseq [dir ["/mnt/settings"
                 "/ujima/settings" "/ujima/storage"
                 "/var/log/journal"]]
      (fs/create-dirs dir))))
