(ns os.ujimaify
  "Runs INSIDE the target chroot as root. 'ujimaifies' the staged system: stages the boot
   services that turn the staged ujimad + desktop into a bootable session — the session
   concern (unit + wrapper + stop path + xinitrc), the persistent journal, the /ujima
   runtime layout — then enables them and stamps the build. File contents (and their
   HW-learned lessons) live in os/ujimaify/<concern>/; this script is the pulls + the few
   actions. Writing/enabling units lives here (rare); *restarting* them for a live
   iteration is the CLI's job (`dev push`), not this script. (fstab is written per-slot
   at install time — ujima.device.ab/install-into-slot! — not here.)

   Pipeline: install -> boot -> base -> ujimad -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! with-console-out]]
            [os.lib.stage :as stage]))


(defn run! [{:keys [project]}]
  (with-console-out
    ;; the desktop session: the supervising unit (PAMName/teardown story lives in the
    ;; file), its stop path, the in-session ujimad launcher, the xinitrc startx runs
    (stage/install! project "ujimaify/session/ujima.service" "/etc/systemd/system/ujima.service")
    (stage/install! project "ujimaify/session/ujimad" "/usr/local/bin/ujimad")
    (stage/install! project "ujimaify/session/ujima-session-stop" "/usr/local/bin/ujima-session-stop")
    (stage/install! project "ujimaify/session/xinitrc" "/etc/X11/xinit/xinitrc")
    ($! systemctl enable "ujima")

    ;; persistent capped journal on storage + the /ujima runtime dirs
    (stage/install! project "ujimaify/journal/ujima.conf" "/etc/systemd/journald.conf.d/ujima.conf")
    (stage/install! project "ujimaify/layout/ujima-layout.conf" "/etc/tmpfiles.d/ujima-layout.conf")

    ;; overlayfs rejects `mount -o remount` via the modern mount API ("No changes allowed in
    ;; reconfigure"), so systemd-remount-fs — which remounts / rw early in boot — fails under the
    ;; overlay root. The overlay already gives us a writable / (the tmpfs upper), so the service is
    ;; redundant: mask it. Pairs with os.boot's cmdline: without one of `overlayroot=tmpfs` or
    ;; `rw` there, / mounts read-only and nothing ever remounts it.
    ($! ln -sf "/dev/null" "/etc/systemd/system/systemd-remount-fs.service")

    ;; build marker
    (spit "/etc/ujima-release"
          (str "built-at=" (java.time.Instant/now) "\n"))))
