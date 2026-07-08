(ns tools.scripts.install
  "Runs INSIDE the target chroot (aarch64 bb under qemu) as root.
   Installs runtime packages and the runtime babashka.

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! with-console-out]]
            [tools.scripts.appcatalog :as appcatalog]))


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

    ;; minimal desktop runtime (cached in the vendor base): i3 + core X + the legacy setuid Xorg.wrap
    ;; (so the systemd session — a non-console user — can open the VT). eww is NOT apt — built from
    ;; source (assets/dev/build-eww), staged by tools.scripts.desktop; libgtk-3-0 is its runtime lib
    ;; (apt pulls the rest of the GTK stack). Pin the exact eww lib set from build-eww's `ldd` dump.
    ($! apt-get install -y --no-install-recommends
        "i3" "xserver-xorg-core" "xserver-xorg-input-libinput" "xinit"
        "xserver-xorg-legacy"
        "libgtk-3-0"
        "libdbusmenu-gtk3-4"  ; eww's systray/dbusmenu runtime lib (pulls libdbusmenu-glib4) — libgtk-3-0 does NOT pull it, so it must be pinned; its absence crash-loops eww on a clean image
        "qt6-gtk-platformtheme" "qt5-gtk-platformtheme"  ; Qt/KDE apps (Marble, Stellarium) follow the GTK Nordic theme — QT_QPA_PLATFORMTHEME=gtk3 on ujima.service (tools.scripts.ujimaify)
        "mesa-vulkan-drivers"  ; v3dv Vulkan driver for the Pi 5 V3D — Godot's Vulkan Mobile renderer
        "librsvg2-common"   ; gdk-pixbuf SVG loader (app icons in file dialogs etc.; librsvg2-2 is just the lib)
        "picom"             ; xrender compositor — transparency for floating dialogs
        "xdotool"           ; synthetic input (startup focus tap; also the dev relay)
        ;; webview launcher host (assets/desktop/bin/ujima-launcher): a chromeless WebKitGTK window
        ;; renders the launcher home surface (served from :1337). python3-gi + the GTK3 / WebKit2-4.1
        ;; typelibs; libgtk-3-0 above is the shared runtime lib. Compositing + JIT disabled in the host.
        "python3-gi" "gir1.2-gtk-3.0" "gir1.2-webkit2-4.1")

    ;; X-from-systemd: let a non-console user start X via the setuid wrapper. HW-verified — without it
    ;; Xorg dies "Cannot open virtual console (Permission denied)".
    (spit "/etc/X11/Xwrapper.config" "allowed_users=anybody\nneeds_root_rights=yes\n")

    ;; Pi 5 splits the render GPU (card0/v3d) from the display (card1); Xorg's autoconfig latches onto
    ;; the render node and dies "no screens found". Mark the vc4 KMS device as the primary GPU.
    ($! mkdir -p "/etc/X11/xorg.conf.d")
    (spit "/etc/X11/xorg.conf.d/99-vc4.conf"
          (str "Section \"OutputClass\"\n"
               "  Identifier \"vc4\"\n"
               "  MatchDriver \"vc4\"\n"
               "  Driver \"modesetting\"\n"
               "  Option \"PrimaryGPU\" \"true\"\n"
               "EndSection\n"))

    ;; audio: per-user PipeWire + WirePlumber (ships wpctl — ujima.linux.audio drives it).
    ;; pipewire-pulse serves the PulseAudio socket (chromium's audio path via libpulse),
    ;; pipewire-alsa routes plain ALSA apps. The user units are preset-enabled at install
    ;; time and start with the session's `systemd --user` (ujima.service logs in via PAM).
    ;; The session is seatless, so /dev/snd access needs the ujima user in `audio` (base).
    ($! apt-get install -y --no-install-recommends
        "pipewire" "pipewire-pulse" "pipewire-alsa" "wireplumber")

    ;; classroom apps the launcher opens: install recipes live beside their catalog specs in
    ;; tools.scripts.app-catalog (one entry per app). Runs HERE so the app packages — libreoffice,
    ;; chromium, inkscape, the fetched TurboWarp, … — bake into the cached vendor base instead of
    ;; re-downloading every build. `apt-get update` above already primed the lists.
    (appcatalog/install! {:project project})

    ;; runtime babashka: the same vendored aarch64 binary we are running under,
    ;; copied + made executable in one shot
    ($! install -m "0755"
                (str project "/assets/tools/bb-aarch64")
                "/usr/local/bin/bb")))
