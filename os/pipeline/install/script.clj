(ns pipeline.install.script
  "Runs INSIDE the target chroot (aarch64 bb under qemu) as root.
   Installs runtime packages and the runtime babashka.

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! with-console-out]]
            [build.apps :as apps]
            [build.deps :as deps]))


(defn run! [{:keys [project]}]
  (with-console-out
    ;; base packages ujimad needs at runtime
    ($! apt-get update)
    ($! apt-get install -y --no-install-recommends "ca-certificates")

    ;; ESCAPE HATCH — the one static file this script writes, deliberately NOT a concern
    ;; file: it must exist BEFORE the overlayroot install below (its postinst trigger runs
    ;; update-initramfs, which SEGFAULTS under qemu), i.e. inside the packages layer. A
    ;; constant 2-liner, so it can't go stale in the vendor cache — which is keyed by the
    ;; package payload set alone, the reason install carries NO other config: content
    ;; written here wouldn't ship until an unrelated package change invalidated the cache.
    ;; We bake a prebuilt, kernel-matched initramfs instead (the boot stage); `=no` also fits the
    ;; immutable model (the initramfs is fixed; a kernel bump is a rebuild, not in-place regen).
    (spit "/etc/initramfs-tools/update-initramfs.conf" "update_initramfs=no\nbackup_initramfs=no\n")

    ;; overlayroot: the read-only-root tmpfs overlay mechanism (lower=ro-root + upper=tmpfs at boot,
    ;; via the initramfs hook baked above). Installed into every image (the cached vendor base);
    ;; activated via the `overlayroot=tmpfs:recurse=0` cmdline token (autoboot.bootfiles/cmdline!),
    ;; and toggled off on dev with the dev-kit lock-fs.
    ($! apt-get install -y --no-install-recommends "overlayroot")

    ;; minimal desktop runtime (cached in the vendor base): i3 + core X + the legacy setuid Xorg.wrap
    ;; (so the systemd session — a non-console user — can open the VT). eww is NOT apt — built from
    ;; source (build-eww, dev kit), staged by the desktop stage; libgtk-3-0 is its runtime lib
    ;; (apt pulls the rest of the GTK stack). Pin the exact eww lib set from build-eww's `ldd` dump.
    ($! apt-get install -y --no-install-recommends
        "i3" "xserver-xorg-core" "xserver-xorg-input-libinput" "xinit"
        "xserver-xorg-legacy"
        "libgtk-3-0"
        "libdbusmenu-gtk3-4"  ; eww's systray/dbusmenu runtime lib (pulls libdbusmenu-glib4) — libgtk-3-0 does NOT pull it, so it must be pinned; its absence crash-loops eww on a clean image
        "qt6-gtk-platformtheme" "qt5-gtk-platformtheme"  ; Qt/KDE apps (Marble, Stellarium) follow the GTK Nordic theme — QT_QPA_PLATFORMTHEME=gtk3 on ujima.service (the ujimaify stage)
        "mesa-vulkan-drivers"  ; v3dv Vulkan driver for the Pi 5 V3D — Godot's Vulkan Mobile renderer
        "librsvg2-common"   ; gdk-pixbuf SVG loader (app icons in file dialogs etc.; librsvg2-2 is just the lib)
        "picom"             ; xrender compositor — transparency for floating dialogs + the transparent shell
        "xdotool"           ; synthetic input (startup focus tap; also the dev relay)
        "x11-xserver-utils" ; xrandr — output/mode inspection + forcing; a display converge needs
                            ; it at RUNTIME on release images, so it is core, not dev-only
        "feh"               ; sets the desktop background (wall.png) on the X root — i3 `exec feh --bg-fill`
        ;; webview launcher host (desktop/bin/ujima-launcher): a chromeless WebKitGTK window
        ;; renders the launcher home surface (served from :1337). python3-gi + the GTK3 / WebKit2-4.1
        ;; typelibs; libgtk-3-0 above is the shared runtime lib. Compositing + JIT disabled in the host.
        "python3-gi" "gir1.2-gtk-3.0" "gir1.2-webkit2-4.1"
        ;; bwrap: opt-in mount isolation for shell-bearing apps (mask the /ujima + /mnt partitions,
        ;; no_new_privs kills sudo inside). Pinned ahead of the wiring — live deploy can't add packages.
        "bubblewrap")

    ;; (the X wrapper + vc4 confs these packages need live in os/base/x11 — base stages
    ;; them, keeping this script packages-only so the vendor cache can't trap config edits)

    ;; audio: per-user PipeWire + WirePlumber (ships wpctl — ujima.linux.audio drives it).
    ;; pipewire-pulse serves the PulseAudio socket (chromium's audio path via libpulse),
    ;; pipewire-alsa routes plain ALSA apps. The user units are preset-enabled at install
    ;; time and start with the session's `systemd --user` (ujima.service logs in via PAM).
    ;; The session is seatless, so /dev/snd access needs the ujima user in `audio` (base).
    ($! apt-get install -y --no-install-recommends
        "pipewire" "pipewire-pulse" "pipewire-alsa" "wireplumber")

    ;; classroom apps the launcher opens: install recipes live in each app's os/apps/<id>/install.edn —
    ;; scanned by build.apps. Runs HERE so the app packages — libreoffice,
    ;; chromium, inkscape, the fetched TurboWarp, … — bake into the cached vendor base instead of
    ;; re-downloading every build. `apt-get update` above already primed the lists.
    (apps/install! project)

    ;; runtime babashka: the same vendored aarch64 binary we are running under,
    ;; copied + made executable in one shot
    ($! install -m "0755"
                (str project "/os/build/vendor/bb-aarch64")
                "/usr/local/bin/bb")

    ;; bb runtime libs (malli, …): sha-verified downloads into /ujima/m2 per the
    ;; committed manifest (bb pin deps) — plain HTTP, no resolver (that needs a JVM
    ;; the image never carries). Like the packages above, a manifest change ships
    ;; only after the vendor cache is rebuilt.
    (deps/install! {:project project})))
