(ns pipeline.ujimaify.script
  "Runs INSIDE the target chroot as root. 'ujimaifies' the staged system: stages the boot
   services that turn the staged runtime + desktop into a bootable session — the session
   concern (unit + stop path + xinitrc), the persistent journal, the /ujima
   runtime layout — then enables them and stamps the build. File contents (and their
   HW-learned lessons) live in os/pipeline/ujimaify/<concern>/; this script is the pulls + the few
   actions. Writing/enabling units lives here (rare); *restarting* them for a live
   iteration is the CLI's job (`dev push`), not this script. (fstab is written per-slot
   at install time — ujima.device.ab/install-into-slot! — not here.)

   Pipeline: install -> boot -> base -> runtime -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! with-console-out]]
            [build.files :as files]))


(defn run! [{:keys [project]}]
  (with-console-out
    ;; the desktop session: the supervising unit (PAMName/teardown story lives in the
    ;; file), its stop path, the xinitrc startx runs
    (files/install! project "ujimaify/session/ujima.service" "/etc/systemd/system/ujima.service")
    (files/install! project "ujimaify/session/ujima-session-stop" "/usr/local/bin/ujima-session-stop")
    (files/install! project "ujimaify/session/xinitrc" "/etc/X11/xinit/xinitrc")
    ($! systemctl enable "ujima")

    ;; persistent capped journal on storage + the /ujima runtime dirs
    (files/install! project "ujimaify/journal/ujima.conf" "/etc/systemd/journald.conf.d/ujima.conf")
    (files/install! project "ujimaify/layout/ujima-layout.conf" "/etc/tmpfiles.d/ujima-layout.conf")

    ;; overlayfs rejects `mount -o remount` via the modern mount API ("No changes allowed in
    ;; reconfigure"), so systemd-remount-fs — which remounts / rw early in boot — fails under the
    ;; overlay root. The overlay already gives us a writable / (the tmpfs upper), so the service is
    ;; redundant: mask it. Pairs with the boot stage's cmdline: without one of `overlayroot=tmpfs` or
    ;; `rw` there, / mounts read-only and nothing ever remounts it.
    ($! ln -sf "/dev/null" "/etc/systemd/system/systemd-remount-fs.service")))
