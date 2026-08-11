(ns tools.cmd.dev
  "Host-side dev-loop commands against a RUNNING ujima dev device over ssh. Distinct from
   pipeline.dev.script (the in-chroot build script).

   Every verb takes the device FIRST (like `ssh <host> <cmd>`), so the payload trails and a
   re-run only edits the tail:

     push <ip> ujimad    deploy ujimad live: run the ujimad script (= `script ujimad` — stages
                         runtime/ src + config into /ujima/ujimad), then restart ujima.service.
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
            [babashka.fs :as fs]
            [babashka.process :as p]
            [build.scripts         :as scripts]
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
