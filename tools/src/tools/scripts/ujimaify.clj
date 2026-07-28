(ns tools.scripts.ujimaify
  "Runs INSIDE the target chroot as root. 'ujimaifies' the staged system: writes + enables the
   systemd units that turn the staged ujimad (and, later, desktop) into boot services, and stamps
   the build. Writing/enabling units lives here (rare); *restarting* them for a live iteration is
   the CLI's job (`dev push`), not this script. (fstab is written per-slot at install time —
   ujima.device.ab/install-into-slot! — not here.)

   Pipeline: install -> base -> ujimad -> desktop -> ujimaify -> [dev] -> [cleanup]."
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]))


;; The graphical session is the boot service now — NOT ujimad. ujimad moved INTO the X
;; session (it owns desktop lifecycle: i3 IPC, eww, app launch, audio — all session-scoped), so it
;; runs as `ujima` via i3 `exec` (see /opt/ujima/desktop/i3/config) through the `ujimad`
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
       ;; Qt/KDE apps (Marble, Stellarium) follow the Nordic GTK theme — a session-wide env inherited by
       ;; startx → Xorg → i3 → ujimad → every launched app. qt6-gtk-platformtheme (install.clj) is the
       ;; GTK→Qt bridge; Qt selects its platform theme from the env only (no /etc file like gtk-3.0).
       "Environment=QT_QPA_PLATFORMTHEME=gtk3\n"
       ;; links an app opens go through ujimad -> the Web app (with mimeapps.list, assets/home)
       "Environment=BROWSER=/opt/ujima/desktop/bin/ujima-open-url\n"
       "StandardOutput=journal\n"
       "Restart=always\n"
       "RestartSec=3\n"
       ;; -br = black root, so the 2-4s of session bring-up shows black, not the grey X weave
       ;; -ac = no X access control: ujimad converges [:system :hostname] mid-session, and xauth
       ;;       cookies are keyed FamilyLocal/<hostname> — the rename strands every later X client
       ;;       ("Authorization required" cold-boot stall, one dead session per boot). Auth gates
       ;;       nobody here anyway (single uid owns ~/.Xauthority; physical access = root). Revisit
       ;;       only if apps ever get their own displays.
       "ExecStart=/usr/bin/startx -- vt1 -br -ac\n"
       ;; PAMName migrates the real session (startx→Xorg→i3→ujimad) into a logind session-N.scope,
       ;; so the unit's OWN cgroup is empty — a plain stop/restart kills only the startx script and
       ;; leaves the whole desktop running as an orphan holding vt1/:0/:1337; the replacement startx
       ;; then crash-loops at exit 1 while the ghost serves OLD code (cost weeks of "flaky Pi").
       ;; ujima-session-stop (below) rides the designed teardown instead: ask ujimad to die,
       ;; then BLOCK on $MAINPID until X is really down — that wait is what TimeoutStopSec bounds.
       ;; `post` runs even after a stop timeout and is the wedged-ujimad last resort: it reaches
       ;; into the logind scope, which the unit's own kill phase never touches. `+` = run as root
       ;; (signal + loginctl rights); both paths exit 0 on an already-dead session, keeping
       ;; crash-recovery restarts working unchanged.
       ;; prestart reaps stale /tmp/.X*-lock files (a hard-killed Xorg can't clean its own):
       ;; without it every hard kill bumps the next session to the next display number and all
       ;; the :0-assuming dev tooling silently breaks (HW: one test storm crept :0 -> :7).
       "ExecStartPre=+/usr/local/bin/ujima-session-stop prestart\n"
       "ExecStop=+/usr/local/bin/ujima-session-stop stop\n"
       "ExecStopPost=+/usr/local/bin/ujima-session-stop post\n"
       "TimeoutStopSec=20\n"
       "\n"
       "[Install]\n"
       "WantedBy=multi-user.target\n"))


;; The in-session ujimad launcher. i3 execs `ujimad` as the session user, so app/window
;; lifecycle + the loopback API run with the session env (DISPLAY, XDG_RUNTIME_DIR) — exactly what
;; they need and what a root boot service couldn't give. Privileged ops still go through `sudo$`
;; (ujima has NOPASSWD), so running as the user costs nothing.
(def ^:private ujimad-wrapper
  (str "#!/bin/sh\n"
       "cd /opt/ujima\n"
       ;; NOT exec: when ujimad exits (crash/OOM), tear the session down with `i3-msg exit` so
       ;; systemd's Restart=always rebuilds it cold — no orphaned eww/app zombies, one startup path.
       "/usr/local/bin/bb -cp src -m ujima.main\n"
       "i3-msg exit\n"))


;; The stop path for ujima.service (ExecStop=`stop` / ExecStopPost=`post`, both root via `+`).
;; The unit's kill phase can't end the session (its cgroup is empty — see ujima-service), so stop
;; does it: stash the logind scope path while $MAINPID (startx) is alive — ExecStop runs BEFORE
;; systemd signals anyone, and post can't derive it once MainPID is gone — ask ujimad to die,
;; then BLOCK until startx exits naturally (it returns only when xinit is done = X down, VT free),
;; which gives TimeoutStopSec something real to bound. `post` runs even after a stop timeout and
;; escalates through logind: TERM first (X restores the VT on TERM; leading with SIGKILL on Xorg
;; corrupts the VT — HW-learned), then KILL only stragglers that ignored TERM (e.g. a SIGSTOPped
;; ujimad still holding :1337) so the next session's ujimad can bind.
(def ^:private ujima-session-stop
  (str "#!/bin/sh\n"
       "STASH=/run/ujima-session-scope\n"
       "\n"
       "stop() {\n"
       "  logger -t ujima-session-stop \"stop: enter mainpid=${MAINPID:-none}\"\n"
       "  [ -n \"${MAINPID:-}\" ] || exit 0\n"
       "  sed -n 's#^0::##p' \"/proc/$MAINPID/cgroup\" 2>/dev/null > \"$STASH\"\n"
       "  pkill -TERM -x -u ujima bb 2>/dev/null\n"
       "  while [ -d \"/proc/$MAINPID\" ]; do sleep 0.2; done\n"
       "  exit 0\n"
       "}\n"
       "\n"
       ;; Survivors = scope members EXCEPT (sd-pam): the PAM helper lives in the scope but exits
       ;; only when the unit's PAM session closes — AFTER ExecStopPost — so counting it makes every
       ;; CLEAN stop look survived, burn the full grace loop, and SIGKILL the PAM cleanup
       ;; (HW-caught: happy-path stop cost 10s instead of ~1s). Occupancy is tested by READING —
       ;; cgroupfs files always stat as size 0, `[ -s ]` is always false there (HW-caught too).
       ;; logger, not echo-to-stderr: ExecStopPost stdio is torn down with the unit and messages
       ;; silently vanish from the journal (HW-caught); logger hits the socket directly.
       "post() {\n"
       "  scope=$(cat \"$STASH\" 2>/dev/null)\n"
       "  logger -t ujima-session-stop \"post: enter scope=${scope:-none}\"\n"
       "  rm -f \"$STASH\"\n"
       "  [ -n \"$scope\" ] || exit 0\n"
       "  procs=\"/sys/fs/cgroup${scope}/cgroup.procs\"\n"
       "  live() {\n"
       "    for p in $(cat \"$procs\" 2>/dev/null); do\n"
       "      [ \"$(cat \"/proc/$p/comm\" 2>/dev/null)\" = \"(sd-pam)\" ] || echo \"$p\"\n"
       "    done\n"
       "  }\n"
       "  [ -n \"$(live)\" ] || exit 0\n"
       "  sid=${scope##*/session-}; sid=${sid%.scope}\n"
       "  names=$(for p in $(live); do cat \"/proc/$p/comm\" 2>/dev/null; done | sort -u | tr \"\\n\" \" \")\n"
       "  logger -t ujima-session-stop \"session $sid: survivors after teardown ($names) - cleaning via logind\"\n"
       "  loginctl terminate-session \"$sid\" 2>/dev/null\n"
       ;; the long grace exists ONLY so a live Xorg can honor TERM and restore the VT (SIGKILL on
       ;; X corrupts it). X already gone = the survivors are stray sleeps/shells: short grace.
       "  case \" $names \" in *\" Xorg \"*) limit=50 ;; *) limit=5 ;; esac\n"
       "  i=0\n"
       "  while [ -n \"$(live)\" ] && [ \"$i\" -lt \"$limit\" ]; do sleep 0.2; i=$((i+1)); done\n"
       "  pids=$(live)\n"
       "  [ -n \"$pids\" ] && kill -9 $pids 2>/dev/null\n"
       "  exit 0\n"
       "}\n"
       "\n"
       ;; a SIGKILLed Xorg (wedged-session escalation, crash) leaves its /tmp/.X<n>-lock behind,
       ;; and the next server silently starts on :<n+1> — reap locks whose pid is dead before
       ;; every start so the session always comes back on :0.
       "prestart() {\n"
       "  for f in /tmp/.X*-lock; do\n"
       "    [ -e \"$f\" ] || continue\n"
       "    pid=$(tr -cd \"0-9\" < \"$f\")\n"
       "    kill -0 \"$pid\" 2>/dev/null && continue\n"
       "    n=${f#/tmp/.X}; n=${n%-lock}\n"
       "    rm -f \"$f\" \"/tmp/.X11-unix/X$n\"\n"
       "    logger -t ujima-session-stop \"prestart: reaped stale X lock :$n (pid ${pid:-none} dead)\"\n"
       "  done\n"
       "  exit 0\n"
       "}\n"
       "\n"
       "case \"$1\" in\n"
       "  stop) stop ;;\n"
       "  post) post ;;\n"
       "  prestart) prestart ;;\n"
       "  *) echo \"usage: ujima-session-stop stop|post|prestart\" >&2; exit 2 ;;\n"
       "esac\n"))


;; Persistent, capped journal — backed by the storage partition via slot->fstab's /var/log/journal
;; bind. Storage=persistent is explicit: `auto` can stay volatile even with the dir present.
(def ^:private journald-conf
  (str "[Journal]\n"
       "Storage=persistent\n"
       "SystemMaxUse=256M\n"))


;; ujimad-writable settings storage (ujimad runs as ujima, the control plane writes scope
;; files). /ujima/run holds the ephemeral scopes (session/activity) — under the overlay it
;; lands in the tmpfs upper, so it must be recreated each boot. The `z` line fixes ownership
;; of the device scope (the per-slot settings bind): the installer chowns it at install time
;; (autoboot/install-into-slot!), but boxes installed before that fix have it root-owned —
;; tmpfiles runs after local-fs.target, i.e. after the bind is mounted, and heals it.
(def ^:private tmpfiles-conf
  (str "d /ujima/run         0755 ujima ujima -\n"
       "d /ujima/run/session 0755 ujima ujima -\n"
       "z /ujima/settings    0755 ujima ujima -\n"
       ;; the kid-facing Files area on the storage partition (tmpfiles runs after local-fs =
       ;; after the mount, healing a fresh/reformatted partition each boot; with storage
       ;; absent — nofail — this lands on the ephemeral upper, degraded but consistent)
       "d /mnt/storage/files 0755 ujima ujima -\n"))


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

    ;; the in-session ujimad launcher (i3 execs this) + the staged xinitrc that startx runs
    (spit "/usr/local/bin/ujimad" ujimad-wrapper)
    ($! chmod "0755" ["/usr/local/bin/ujimad"])
    (spit "/usr/local/bin/ujima-session-stop" ujima-session-stop)
    ($! chmod "0755" ["/usr/local/bin/ujima-session-stop"])
    (fs/create-dirs "/etc/X11/xinit")
    ($! cp "/opt/ujima/desktop/xinitrc" "/etc/X11/xinit/xinitrc")

    ;; persistent capped journal on storage
    (fs/create-dirs "/etc/systemd/journald.conf.d")
    (spit "/etc/systemd/journald.conf.d/ujima.conf" journald-conf)

    ;; ujimad-writable settings dirs: ephemeral scopes each boot + device-scope ownership heal
    (spit "/etc/tmpfiles.d/ujima.conf" tmpfiles-conf)

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
