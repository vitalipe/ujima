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

    ;; minimal desktop runtime (cached in the vendor base): i3 + core X + the legacy setuid Xorg.wrap
    ;; (so the systemd session — a non-console user — can open the VT). eww is NOT apt — built from
    ;; source (assets/dev/build-eww), staged by tools.scripts.desktop; libgtk-3-0 is its runtime lib
    ;; (apt pulls the rest of the GTK stack). Pin the exact eww lib set from build-eww's `ldd` dump.
    ($! apt-get install -y --no-install-recommends
        "i3" "xserver-xorg-core" "xserver-xorg-input-libinput" "xinit"
        "xserver-xorg-legacy"
        "libgtk-3-0"
        "librsvg2-common"   ; gdk-pixbuf SVG loader — lets eww render the SVG app icons (librsvg2-2 is just the lib)
        "picom"             ; xrender compositor — transparency for floating eww overlays (volume popover)
        "xdotool")          ; one synthetic tap at startup wakes the override-redirect bars' input (ujima.desktop/wake-bars!)

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

    ;; classroom apps the launcher opens (assets/desktop/apps.edn): chromium for the web tiles
    ;; (Wikipedia/Books, run as --app), libreoffice-writer for Write, tuxpaint for Draw, pcmanfm for
    ;; Files. Writer-only (not the full suite) to match the catalog + keep the image lean.
    ;; libreoffice-gtk3 = the GTK3 VCL plugin: without it LO ignores the system GTK theme and
    ;; draws its own light chrome — the desktop step's Nordic/dark styling never reaches Write.
    ($! apt-get install -y --no-install-recommends
        "chromium" "libreoffice-writer" "libreoffice-gtk3" "tuxpaint" "pcmanfm")

    ;; runtime babashka: the same vendored aarch64 binary we are running under,
    ;; copied + made executable in one shot
    ($! install -m "0755"
                (str project "/assets/tools/bb-aarch64")
                "/usr/local/bin/bb")))
