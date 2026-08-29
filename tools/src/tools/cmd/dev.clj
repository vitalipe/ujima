(ns tools.cmd.dev
  "Host-side dev-loop commands against a RUNNING ujima dev device over ssh. Distinct from
   pipeline.dev.script (the in-chroot build script).

   Every verb takes the device FIRST (like `ssh <host> <cmd>`), so the payload trails and a
   re-run only edits the tail:

     push <ip> ujimad    deploy ujimad live: run the ujimad script (= `script ujimad` — stages
                         runtime/ src + config into /ujima/ujimad), then restart ujima.service.
     upgrade <ip> <pack> install a pack into the device's INACTIVE slot and carry its settings
                         over. Touches no boot config; re-running is cheap.
     boot <ip>           try-boot the prepared slot, then keep it (--no-commit to stay on trial).
     script <ip> <name>  run <name>.script/run! live on the device — the running-system
                         analog of `bb os script` (which runs the same fn in the build chroot).
     view <ip>           interactive x11vnc mirror of the device's :0 desktop — mouse + keyboard live.
     screenshot <ip>     pull a one-frame PNG of :0 to the host (a quick look / for Claude to verify).
     click <ip> x y      synthetic pointer click at (x,y) on :0 (xdotool).
     type <ip> <text>    type a literal string on :0 (xdotool).
     key <ip> <chord>    send a key/chord on :0 — e.g. ctrl+f, Return, super+2 (xdotool).

   All talk to the device over sshpass+ssh with default ujima/ujima creds (dev boxes; see the
   public-access threat model). No live-safe gating: `script` runs whatever you name — note
   cleanup/base/ujimaify are destructive on a running box."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [build.scripts         :as scripts]
            [ujima.pack :as ujima-pack]
            [lib.task.flow :refer [flow <step!]]
            [lib.shell :refer [sh! sh?]]))


(defn- require-host-cmd! [cmd hint]
  (when-not (fs/which cmd)
    (throw (ex-info (str cmd " not found on host — " hint) {:cmd cmd}))))


;; ---------------------------------------------------------------------------
;; ssh transport (shared by push + script)
;; ---------------------------------------------------------------------------

(defn ssh-transport
  "Derive the ssh wiring for a device from opts: the ssh option vector, the `-e` transport string
   rsync drives ssh through, and user@host. StrictHostKeyChecking off + throwaway known_hosts —
   these are dev boxes that get reflashed."
  [{:keys [ip user password port]}]
  (let [ssh-opts ["-p" port "-o" "StrictHostKeyChecking=no" "-o" "UserKnownHostsFile=/dev/null"]]
    {:ssh-opts ssh-opts
     :ssh-e    (str "sshpass -p " password " ssh " (str/join " " ssh-opts))
     :host     (str user "@" ip)
     :password password}))


(defn- remote-sh?
  "Run `cmd` (one shell string) on the device, capturing. Returns the sh? result map ({:ok? …}).
   For preflight probes."
  [{:keys [password ssh-opts host]} cmd]
  (apply sh? :sshpass "-p" password "ssh" (concat ssh-opts [host cmd])))


(defn remote-exec!
  "Run `cmd` (one shell string) on the device, streaming stdout/stderr live to the console
   (inherit). Throws on a nonzero remote exit, so a failed script surfaces loudly host-side."
  [{:keys [password ssh-opts host]} cmd]
  (apply p/shell {:inherit true} "sshpass" "-p" password "ssh" (concat ssh-opts [host cmd])))


;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------


;; staging dir on the device = the chroot repo bind path; NOT the install target —
;; the ujimad script copies <project>/runtime/src into /ujima/ujimad, staging there would
;; copy it into itself
(def ^:private device-stage scripts/project-mnt)

;; Repo subset staged to the device — the dirs scripts read plus what the bb classpath needs.
;; Explicit include-list, NEVER the whole worktree: it holds tens of GB of build output under
;; out/ plus private untracked files, none of which should go over the wire to a Pi. A new
;; script that reads a new asset dir adds one entry here.
(def ^:private stage-paths ["runtime/src" "runtime/config"
                            "os"          ; pipeline scripts + concern dirs + build machinery (incl. vendored bb)
                            "desktop"])


(defn script!
  "Run an image script (<name>.script/run!) on a RUNNING dev device over ssh — the live
   analog of `bb os script`. Stages the repo subset the scripts need to device-stage, then
   runs the device's own bb against it as root. No chroot, no qemu (native aarch64), no host root."
  [{:keys [script ip] :as opts}]
  (scripts/require-script! script)                    ; fail fast, before any ssh/rsync
  (require-host-cmd! "sshpass" "install it (e.g. apt install sshpass)")
  (require-host-cmd! "rsync"   "install it (e.g. apt install rsync)")
  (let [{:keys [ssh-e host] :as transport} (ssh-transport opts)
        ;; resolve bb AS THE LOGIN USER first ($(command -v bb) on its PATH), then sudo the
        ;; absolute path: sudo's secure_path won't include the vendored bb, so a bare `sudo bb`
        ;; would be command-not-found.
        remote-cmd (str "sudo \"$(command -v bb)\" "
                        (str/join " " (scripts/run-args script device-stage)))]
    ;; device preflight (loud): need rsync to stage and bb to run
    (doseq [c ["rsync" "bb"]]
      (when-not (:ok? (remote-sh? transport (str "command -v " c " >/dev/null")))
        (throw (ex-info (str c " missing on " ip
                             " — install it on the device or reflash a dev image that ships it")
                        {:ip ip :cmd c}))))
    ;; stage the subset (-R recreates the runtime/src os … layout under device-stage). No
    ;; --chmod: preserve source perms so executables (dev-kit wifi, vendored bb) stay +x.
    ;; root-owned, remote rsync elevated via passwordless sudo.
    (apply sh! :rsync "-aR" "--delete"
           "--chown=root:root"
           "--rsync-path=sudo rsync"
           "-e" ssh-e
           (concat stage-paths [(str host ":" device-stage "/")]))
    (println "staged" (str/join " " stage-paths) "->" (str ip ":" device-stage))
    (println (str "running the " script " script on " ip))
    (remote-exec! transport remote-cmd)
    {:script script :ip ip :stage device-stage}))


;; What `push <target>` knows how to deploy: the image script that stages it (the copy) and the
;; systemd unit to restart so the new code takes effect. A new deployable target is one entry.
(def ^:private push-targets
  {"ujimad" {:script "ujimad" :service "ujima"}})


(defn- ujimad-pid
  "The device's running ujimad pid as a string, nil when none."
  [transport]
  (let [{:keys [ok? out]} (remote-sh? transport "pgrep -x bb | head -1")]
    (when ok? (not-empty (str/trim (str out))))))


(defn push!
  "Entry for `dev push <ip> <target>`: stage + run the target's image script (the copy, via
   script!), then restart its systemd unit so the new code is live — and REFUSE to report
   success until a FRESH ujimad pid is seen. The PAMName/logind session leak (see ujimaify
   session/ujima.service) once let a 'successful' restart leave an orphan session serving OLD code;
   never trust a push without a new pid. Today only \"ujimad\" (the ujimad script ->
   ujima.service); a new target is one entry in push-targets."
  [{:keys [target ip] :as opts}]
  (if-let [{:keys [script service]} (get push-targets target)]
    (let [result    (script! (assoc opts :script script))
          transport (ssh-transport opts)
          old-pid   (ujimad-pid transport)]
      (println (str "restarting " service " on " ip))
      (remote-exec! transport (str "sudo systemctl restart " service))
      (println "waiting for a fresh ujimad...")
      (loop [tries 0]
        (let [pid (ujimad-pid transport)]
          (cond
            (and pid (not= pid old-pid))
            (println (str "ujimad is fresh (pid " pid ", was " (or old-pid "none") ")"))

            (>= tries 30)
            (throw (ex-info (str "ujimad did not cycle on " ip " — the old session survived the "
                                 "restart; recover with: ssh + `pkill -TERM -x bb`")
                            {:ip ip :old-pid old-pid}))

            :else (do (Thread/sleep 3000) (recur (inc tries))))))
      (println (str "tail logs:  journalctl -u " service " -f"))
      (assoc result :restarted service))
    (throw (ex-info (str "unknown push target: " target
                         " — supported: " (str/join ", " (keys push-targets)))
                    {:target target}))))


;; ---------------------------------------------------------------------------
;; Upgrade — the A/B machinery against the dev device itself. The pack is PUSHED as a file
;; and installed by the device's OWN runtime; settings are carried by exporting from the
;; running slot and importing through the NEW slot's migration ns. Split by decision:
;; `upgrade` prepares a slot and touches no boot config, `boot` goes there and keeps it.
;; ---------------------------------------------------------------------------

(def ^:private device-script "tools/device/upgrade.clj")
(def ^:private device-script-path "/tmp/ujima-upgrade.clj")
(def ^:private packs-dir "/ujima/storage/updates")

;; the export carries the wifi psk, so it lives 0600 on tmpfs for the extent of one upgrade
(def ^:private commands-path "/tmp/ujima-migrate.edn")


(defn- installed-runtime-cp
  "The device's own classpath, the way its ujimad launcher builds it. Explicit because bb's
   resolver needs a JVM the image doesn't carry."
  []
  "src$(find /ujima/m2 -name '*.jar' -printf ':%p' 2>/dev/null)")


(defn- runtime-cmd
  "Run an installed runtime ns on the device, unprivileged."
  [ns-name & args]
  (str "cd /ujima/ujimad && bb -cp \"" (installed-runtime-cp) "\" -m " ns-name
       (when (seq args) (str " " (str/join " " args)))))


(defn- script-cmd
  "Run the shipped script against the installed runtime, as root. bb is resolved as the
   login user first — sudo's secure_path does not include it."
  [& args]
  (str "cd /ujima/ujimad && sudo \"$(command -v bb)\" -cp \"" (installed-runtime-cp) "\" "
       device-script-path " " (str/join " " args)))


(defn- ship-script!
  "Put the script on the device's tmpfs. Re-shipped after every reboot — / is a tmpfs
   overlay and nothing under /tmp survives one."
  [{:keys [password ssh-opts host]}]
  (apply sh! {:in (slurp device-script)} :sshpass "-p" password "ssh"
         (concat ssh-opts [host (str "cat > " device-script-path)])))


(defn- edn-line
  "The one line of OUT that reads as an EDN map — the runtime logs to the same stdout."
  [out what]
  (or (->> (str/split-lines (str out))
           (keep (fn [line]
                   (when (str/starts-with? (str/trim line) "{")
                     (try (edn/read-string line) (catch Throwable _ nil)))))
           (last))
      (throw (ex-info (str "could not read " what " from the device") {:output (str out)}))))


(defn- device-status [transport]
  (let [{:keys [ok? out err]} (remote-sh? transport (script-cmd "status"))]
    (when-not ok?
      (throw (ex-info "could not read the device's A/B status" {:error (str err out)})))
    (edn-line out "the device's A/B status")))


(defn- require-migration-ns!
  "The exporter runs on the RUNNING slot, so that slot has to carry it — one `bb dev push
   ujimad` away."
  [{:keys [ip] :as transport}]
  (when-not (:ok? (remote-sh? transport "test -f /ujima/ujimad/src/ujima/migration.clj"))
    (throw (ex-info (str ip " is running a slot with no ujima.migration — the settings export "
                         "lives there. Push it first:  bb dev push " ip " ujimad")
                    {:ip ip}))))


(defn- free-bytes [transport path]
  (let [{:keys [ok? out]} (remote-sh? transport (str "df -B1 --output=avail " path " | tail -1"))]
    (when ok? (parse-long (str/trim (str out))))))


(defn- same-pack-installed?
  "Whether the target slot already holds exactly this pack — the install record carries the
   manifest verbatim. This is what makes a re-run cheap after a failed migration."
  [status pack target]
  (= (:members (ujima-pack/manifest pack))
     (get-in status [:disk :slots target :ujima-os :members])))


(defn- target-slot [status]
  (if (= :a (:running-slot status)) :b :a))


(defn- push-pack!
  "rsync the pack onto the device's storage. --partial so an interrupted ship resumes."
  [{:keys [ssh-e host] :as transport} pack]
  (let [dest  (str packs-dir "/" (fs/file-name pack))
        need  (fs/size pack)
        free  (free-bytes transport packs-dir)]

    ;; capturing, not inherit: this runs inside a rendered task step
    (remote-sh? transport (str "sudo mkdir -p " packs-dir " && sudo chown ujima:ujima " packs-dir))

    (when (and free (< free need))
      (throw (ex-info (str "not enough room on the device for the pack — need "
                           (format "%.1f" (/ need 1e9)) " GB, have "
                           (format "%.1f" (/ free 1e9)) " GB free at " packs-dir)
                      {:need need :free free})))

    ;; no --info=progress2: its carriage returns fight the task line lib.cli renders
    (sh! :rsync "-a" "--partial" "--inplace"
         "-e" ssh-e (str pack) (str host ":" dest))
    dest))


(defn- export-settings
  "The EXPORT half, run by the RUNNING slot's own migration ns. Not the API's settings
   query — that strips :secret?, and the psk is what makes the new slot reachable."
  [transport]
  (let [{:keys [ok? out err]} (remote-sh? transport (runtime-cmd "ujima.migration" "export"))]
    (when-not ok?
      (throw (ex-info "settings export failed on the device" {:error (str err out)})))
    (or (->> (str/split-lines (str out))
             (keep (fn [line]
                     (when (str/starts-with? (str/trim line) "[")
                       (try (edn/read-string line) (catch Throwable _ nil)))))
             (last))
        (throw (ex-info "could not read the settings export from the device"
                        {:output (str out)})))))


(defn upgrade!
  "Prepare the device's inactive slot: ship the pack, install it, carry the settings over.
   Touches no boot config — `bb dev boot` is what goes there. Returns a cold flow the CLI
   wrapper runs and renders."
  [{:keys [ip pack] :as opts}]
  (require-host-cmd! "sshpass" "install it (e.g. apt install sshpass)")
  (require-host-cmd! "rsync"   "install it (e.g. apt install rsync)")

  ;; host-side and first: we hold the file, so a bad pack costs nothing to find out about
  (ujima-pack/validate! pack)

  (let [transport (ssh-transport opts)
        {:keys [password ssh-opts host]} transport
        _         (require-migration-ns! transport)
        _         (ship-script! transport)
        status    (device-status transport)
        running   (:running-slot status)
        target    (target-slot status)
        installed (same-pack-installed? status pack target)
        dest      (str packs-dir "/" (fs/file-name pack))
        rm!       (fn [path] (apply sh? :sshpass "-p" password "ssh"
                                    (concat ssh-opts [host (str "rm -f " path)])))]

    ;; the plan prints normally, before the task line takes over the cursor
    (println (str ip " runs from slot " (name running) " -> preparing slot " (name target)))
    (when installed
      (println (str "slot " (name target) " already holds this exact pack — its install record "
                    "matches the manifest, so the ship and the install are skipped")))

    (flow :upgrade
      (<step! 55 :ship
        (if installed
          (progress! 100 "pack already installed")
          (do (progress! 0 (str (format "%.2f" (/ (fs/size pack) 1e9)) " GB -> " ip " (~13 min)"))
              (push-pack! transport pack))))

      (<step! 85 :install
        (if installed
          (progress! 100 "slot already written")
          (do (progress! 0 (str "writing slot " (name target) " (boot + root, ~10.5G)"))
              (try
                (remote-exec! transport (script-cmd "install" dest))
                (finally
                  ;; idempotency comes from the install record, not a resident 4.4G pack
                  (rm! dest))))))

      (<step! 100 :migrate
        (progress! 0 "exporting this slot's settings")
        (let [commands (export-settings transport)]

          (when (empty? commands)
            (error! :nothing-to-carry
                    (str "the device has nothing set to carry forward — refusing a migration "
                         "that would silently produce a defaults-only slot")))

          (progress! 50 (str "carrying " (count commands) " settings into slot " (name target)))

          ;; umask, not chmod: the psk must never exist world-readable, not even briefly
          (apply sh! {:in (pr-str commands)} :sshpass "-p" password "ssh"
                 (concat ssh-opts [host (str "umask 077 && cat > " commands-path)]))

          (try
            ;; the device prints what the target slot refused; a newline first so its report
            ;; lands under the task line rather than inside it
            (println)
            (remote-exec! transport (script-cmd "migrate" commands-path))
            (finally
              (rm! commands-path)))))

      (println (str "slot " (name target) " is ready. Boot it with:  bb dev boot " ip))
      {:ip ip :pack (str pack) :from running :slot target})))


(defn- await-reboot!
  "Wait out a reboot: first for the device to DROP, then for it to answer again. Both halves
   are needed — `reboot` returns long before the box goes down."
  [transport down-ms up-ms]
  (let [gone-by (+ (System/currentTimeMillis) down-ms)]
    (while (and (< (System/currentTimeMillis) gone-by)
                (:ok? (remote-sh? transport "true")))
      (Thread/sleep 2000)))

  (let [give-up (+ (System/currentTimeMillis) up-ms)]
    (loop []
      (cond
        (:ok? (remote-sh? transport "true")) true

        (> (System/currentTimeMillis) give-up)
        (throw (ex-info "the device did not come back — a slot that never boots is never
                         committed, so power-cycle it and it falls back on its own"
                        {:waited-ms up-ms}))

        :else (do (Thread/sleep 5000) (recur))))))


(defn boot!
  "Try-boot the prepared slot and keep it. A slot that never boots never reconnects, so it
   never gets committed and a power cycle falls back. `--no-commit` leaves it on trial."
  [{:keys [ip no-commit] :as opts}]
  (require-host-cmd! "sshpass" "install it (e.g. apt install sshpass)")
  (let [transport (ssh-transport opts)
        {:keys [password ssh-opts host]} transport
        _   (ship-script! transport)
        was (:running-slot (device-status transport))]

    (flow :boot
      (<step! 10 :tryboot
        (progress! 0 (str "try-booting out of slot " (name was)))
        ;; the box goes down under us, so a dropped session here is success, not failure
        (apply sh? :sshpass "-p" password "ssh"
               (concat ssh-opts [host (script-cmd "boot")])))

      (<step! 80 :await
        (progress! 0 "waiting for it to drop and come back")
        (await-reboot! transport (* 60 1000) (* 5 60 1000))
        ;; / is a tmpfs overlay, so nothing we put under /tmp survived the reboot
        (ship-script! transport))

      (<step! 100 :keep
        (let [status (device-status transport)
              now    (:running-slot status)]
          (if no-commit
            (progress! 100 (str "on slot " (name now) ", left on TRIAL"))
            (do (progress! 50 (str "committing slot " (name now)))
                (remote-exec! transport (script-cmd "commit"))))

          (println)
          (println (str "came up on slot " (name now) " (was " (name was) "), version "
                        (get-in status [:disk :slots now :ujima-os :image :version])))
          (if no-commit
            (println (str "left on trial — a plain reboot falls back to slot " (name was)))
            (println (str "slot " (name now) " is now the boot slot")))))

      {:ip ip :from was :committed (not no-commit)})))


;; ---------------------------------------------------------------------------
;; Screen relay (desktop iteration). view = interactive x11vnc (mouse+keyboard), screenshot =
;; one-shot maim PNG. Both ride the shared ssh-transport; their device tools are baked DEV-ONLY by
;; the dev stage (a VNC server must never ship in a release image).
;; ---------------------------------------------------------------------------

(defn screenshot!
  "Pull a single PNG frame of the device's live :0 desktop to the host — the still that lets you (or
   Claude) eyeball a desktop change without opening the interactive viewer. maim writes the PNG to
   stdout on the device; ssh (no PTY) forwards it 8-bit-clean straight into the host file. Nothing is
   left on the device (its root is an ephemeral overlay)."
  [{:keys [ip out display xauth] :as opts}]
  (require-host-cmd! "sshpass" "install it (e.g. apt install sshpass)")
  (let [{:keys [password ssh-opts host] :as transport} (ssh-transport opts)
        outf (or out "tmp/screen/ujima-screen.png")]   ;; tmp/ is gitignored — no repo-root droppings
    (some-> (fs/parent outf) fs/create-dirs)   ;; nil parent = bare filename in cwd
    (when-not (:ok? (remote-sh? transport "command -v maim >/dev/null"))
      (throw (ex-info (str "maim missing on " ip " — run `bb dev script " ip " dev` to bake it (or reflash a dev image that ships it)")
                      {:ip ip :cmd "maim"})))
    (let [remote-cmd (str "DISPLAY=" display " XAUTHORITY=" xauth " maim")
          {:keys [exit]} @(apply p/process {:out :write :out-file (fs/file outf) :err :inherit}
                                 "sshpass" "-p" password "ssh" (concat ssh-opts [host remote-cmd]))]
      (when-not (zero? exit)
        (throw (ex-info (str "remote maim exited " exit " — is the desktop session up on " display "?")
                        {:ip ip :exit exit})))
      (when (or (not (fs/exists? outf)) (zero? (fs/size outf)))
        (throw (ex-info "screenshot produced no data" {:ip ip :out (str outf)})))
      (println "saved" (str outf))
      {:ip ip :out (str outf)})))


;; Host VNC viewers we know how to launch, best first. None ship by default —
;; `sudo apt install tigervnc-viewer` provides xtigervncviewer.
(def ^:private vnc-viewers ["xtigervncviewer" "vncviewer" "gvncviewer" "remmina" "vinagre"])

(defn view!
  "Open an INTERACTIVE live view of the device's :0 desktop — your mouse + keyboard land on the real
   session (x11vnc, via XTEST). One ssh process both forwards the RFB port (`-L`) and runs x11vnc
   bound to the device loopback (`-localhost`, never exposed); we wait for it to bind, then launch a
   host VNC viewer through the tunnel. Closing the viewer tears down the ssh process (and its x11vnc)."
  [{:keys [ip rfbport display xauth] :as opts}]
  (require-host-cmd! "sshpass" "install it (e.g. apt install sshpass)")
  (let [viewer (or (some #(when (fs/which %) %) vnc-viewers)
                   (throw (ex-info "no VNC viewer on host — `sudo apt install tigervnc-viewer` (xtigervncviewer)"
                                   {:tried vnc-viewers})))
        {:keys [password ssh-opts host] :as transport} (ssh-transport opts)]
    (when-not (:ok? (remote-sh? transport "command -v x11vnc >/dev/null"))
      (throw (ex-info (str "x11vnc missing on " ip " — run `bb dev script " ip " dev` to bake it (or reflash a dev image that ships it)")
                      {:ip ip :cmd "x11vnc"})))
    (let [x11vnc (str "x11vnc -display " display " -auth " xauth
                      " -localhost -rfbport " rfbport " -nopw -forever")
          server (apply p/process {:out :inherit :err :inherit}
                        "sshpass" "-p" password "ssh" "-L" (str rfbport ":localhost:" rfbport)
                        (concat ssh-opts [host x11vnc]))]
      (try
        ;; Wait for x11vnc to bind on the DEVICE loopback (bash /dev/tcp — no nc needed). The ssh -L
        ;; socket accepts host-side the moment ssh authenticates, so a host-side poll would pass too
        ;; early; probe the remote port instead.
        (when-not (:ok? (remote-sh? transport
                          (str "for i in $(seq 1 50); do "
                               "(exec 3<>/dev/tcp/localhost/" rfbport ") 2>/dev/null && exit 0; "
                               "sleep 0.1; done; exit 1")))
          (throw (ex-info "x11vnc did not start on the device in time" {:ip ip :rfbport rfbport})))
        (println (str "connecting " viewer " -> " ip ":0  (mouse + keyboard live; close the window to end)"))
        (p/shell {:inherit true :continue true} viewer (str "localhost:" rfbport))
        {:ip ip :rfbport rfbport :viewer viewer}
        (finally
          (p/destroy-tree server))))))


;; ---------------------------------------------------------------------------
;; Synthetic input (xdotool on :0) — the programmatic sibling of `dev view`: drive the desktop
;; headlessly in a loop (screenshot -> click/type/key -> screenshot to verify). xdotool is baked
;; DEV-ONLY by the dev stage; screenshot pixels map 1:1 to xdotool coords.
;; ---------------------------------------------------------------------------

(defn- sh-quote
  "POSIX single-quote escaping, so an arbitrary string (spaces, quotes, $) survives the remote
   shell as one literal argument."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn- xdo!
  "Run `xdotool <xargs>` on the device's :0 over ssh, with a loud preflight that xdotool is present.
   `xargs` is everything after `xdotool`, already assembled + quoted."
  [{:keys [ip display xauth] :as opts} xargs]
  (require-host-cmd! "sshpass" "install it (e.g. apt install sshpass)")
  (let [transport (ssh-transport opts)]
    (when-not (:ok? (remote-sh? transport "command -v xdotool >/dev/null"))
      (throw (ex-info (str "xdotool missing on " ip " — run `bb dev script " ip " dev` to bake it (or reflash a dev image that ships it)")
                      {:ip ip :cmd "xdotool"})))
    (remote-exec! transport
                  (str "DISPLAY=" display " XAUTHORITY=" xauth " xdotool " xargs))))

(defn click!
  "Move the pointer to (x,y) on the device's :0 and click — synthetic input via xdotool. (x,y) are
   screenshot pixels (1:1 with xdotool coords); --count 2 double-clicks."
  [{:keys [x y button count] :as opts}]
  (xdo! opts (str "mousemove " x " " y " click --repeat " count " " button))
  (println (str "clicked button " button (when (not= count "1") (str " x" count)) " at " x "," y))
  {:x x :y y :button button :count count})

(defn type!
  "Type a literal string on the device's :0 via xdotool (single-quote-escaped for the remote shell,
   so spaces/quotes are safe)."
  [{:keys [text delay] :as opts}]
  (xdo! opts (str "type --clearmodifiers --delay " delay " -- " (sh-quote text)))
  (println (str "typed " (clojure.core/count text) " chars"))
  {:typed (clojure.core/count text)})

(defn key!
  "Send a key or chord on the device's :0 via xdotool — e.g. `ctrl+f`, `Return`, `super+2`."
  [{:keys [chord] :as opts}]
  (xdo! opts (str "key --clearmodifiers " (sh-quote chord)))
  (println (str "key " chord))
  {:key chord})


;; ── the CLI ─────────────────────────────────────────────────────────────────

(def cli
  {"dev"
   {"push"
    {:usage "Usage: dev push <ip> ujimad [--user ujima] [--password ujima] [--port 22]"
     :target push!
     :args [:ip :target]
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :target   {:desc "What to push (ujimad)" :require true :coerce :string}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}

    "upgrade"
    {:usage "Usage: dev upgrade <ip> <pack> [--user ujima] [--password ujima] [--port 22]"
     :target upgrade!
     :args [:ip :pack]
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :pack     {:desc "The .pack to install into the device's INACTIVE slot" :require true :coerce :string}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}

    "boot"
    {:usage "Usage: dev boot <ip> [--no-commit] [--user ujima] [--password ujima] [--port 22]"
     :target boot!
     :args [:ip]
     :spec {:ip        {:desc "Target RPI host or IP" :require true :coerce :string}
            :no-commit {:coerce :boolean
                        :desc "Leave the slot on trial — a plain reboot falls back"}
            :user      {:desc "SSH user"     :default "ujima" :coerce :string}
            :password  {:desc "SSH password" :default "ujima" :coerce :string}
            :port      {:desc "SSH port"     :default "22"    :coerce :string}}}

    "script"
    {:usage "Usage: dev script <ip> <name> [--user ujima] [--password ujima] [--port 22]"
     :target script!
     :args [:ip :script]
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :script   {:desc "pipeline script to run live on the device" :require true :coerce :string}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}

    "view"
    {:usage "Usage: dev view <ip> [--rfbport 5900] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target view!
     :args [:ip]
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :rfbport  {:desc "VNC/RFB port (tunneled over ssh)" :default "5900" :coerce :string}
            :display  {:desc "X display to mirror" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}

    "screenshot"
    {:usage "Usage: dev screenshot <ip> [--out tmp/screen/ujima-screen.png] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target screenshot!
     :args [:ip]
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :out      {:desc "Host PNG output path" :default "tmp/screen/ujima-screen.png"}
            :display  {:desc "X display to grab" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}

    "click"
    {:usage "Usage: dev click <ip> <x> <y> [--button 1] [--count 1] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target click!
     :args [:ip :x :y]
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :x        {:desc "X coordinate on :0 (screenshot px = xdotool coord)" :require true :coerce :string}
            :y        {:desc "Y coordinate on :0" :require true :coerce :string}
            :button   {:desc "Mouse button (1=left 2=mid 3=right)" :default "1" :coerce :string}
            :count    {:desc "Click count (2 = double-click)" :default "1" :coerce :string}
            :display  {:desc "X display" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}

    "type"
    {:usage "Usage: dev type <ip> <text> [--delay 40] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target type!
     :args [:ip :text]
     ;; :coerce :string or babashka.cli parses digit-only args as numbers — `dev type <ip> 42`
     ;; then dies in the arg handling instead of typing "42"
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :text     {:desc "Literal text to type on :0" :require true :coerce :string}
            :delay    {:desc "ms between keystrokes" :default "40" :coerce :string}
            :display  {:desc "X display" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}

    "key"
    {:usage "Usage: dev key <ip> <chord> [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target key!
     :args [:ip :chord]
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :chord    {:desc "Key or chord, e.g. ctrl+f, Return, super+2" :require true :coerce :string}
            :display  {:desc "X display" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}}})
