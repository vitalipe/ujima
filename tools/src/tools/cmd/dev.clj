(ns tools.cmd.dev
  "Host-side dev-loop commands against a RUNNING ujima dev device over ssh. Distinct from
   tools.scripts.dev (the in-chroot build script).

     push agent      deploy the agent live: run tools.scripts.agent (= `script agent` — stages
                     src/ + config into /opt/ujima), then restart ujima.service.
     script <name>   run tools.scripts.<name>/run! live on the device — the running-system
                     analog of `tools image script` (which runs the same fn in the build chroot).

   Both talk to the device over sshpass+ssh with default ujima/ujima creds (dev boxes; see the
   public-access threat model). No live-safe gating: `script` runs whatever you name — note
   cleanup/base/ujimaify are destructive on a running box."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [lib.shell :refer [sh! sh?]]
            [tools.script-registry :as registry]))


(defn- require-host-cmd! [cmd hint]
  (when-not (fs/which cmd)
    (throw (ex-info (str cmd " not found on host — " hint) {:cmd cmd}))))


;; ---------------------------------------------------------------------------
;; ssh transport (shared by push + script)
;; ---------------------------------------------------------------------------

(defn- ssh-transport
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


(defn- remote-exec!
  "Run `cmd` (one shell string) on the device, streaming stdout/stderr live to the console
   (inherit). Throws on a nonzero remote exit, so a failed script surfaces loudly host-side."
  [{:keys [password ssh-opts host]} cmd]
  (apply p/shell {:inherit true} "sshpass" "-p" password "ssh" (concat ssh-opts [host cmd])))


;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------


;; The staging dir on the device. NOT /opt/ujima: agent.clj copies <project>/src into
;; /opt/ujima/, so if project were /opt/ujima it would copy src into itself. Mirrors the chroot,
;; where the read-only repo bind (/ujima-src) is deliberately separate from the install target.
(def ^:private device-stage "/ujima-src")

;; Repo subset staged to the device — the dirs scripts read plus what the bb classpath needs.
;; Explicit include-list, NEVER the whole worktree: it holds the 846MB assets/e2e/dummy.pack and
;; other large/private untracked files that must never go over the wire to a Pi. A new script
;; that reads a new asset dir adds one entry here.
(def ^:private stage-paths ["src" "tools/src" "config" "assets/dev" "assets/tools"])


(defn script!
  "Run an image script (tools.scripts.<name>/run!) on a RUNNING dev device over ssh — the live
   analog of `tools image script`. Stages the repo subset the scripts need to device-stage, then
   runs the device's own bb against it as root. No chroot, no qemu (native aarch64), no host root."
  [{:keys [script ip] :as opts}]
  (registry/require-script! script)                  ; fail fast, before any ssh/rsync
  (require-host-cmd! "sshpass" "install it (e.g. apt install sshpass)")
  (require-host-cmd! "rsync"   "install it (e.g. apt install rsync)")
  (let [{:keys [ssh-e host] :as transport} (ssh-transport opts)
        cp         (str device-stage "/src:" device-stage "/tools/src")
        ;; resolve bb AS THE LOGIN USER first ($(command -v bb) on its PATH), then sudo the
        ;; absolute path: sudo's secure_path won't include the vendored bb, so a bare `sudo bb`
        ;; would be command-not-found.
        remote-cmd (str "sudo \"$(command -v bb)\""
                        " --classpath " cp
                        " -x tools.scripts." script "/run!"
                        " --project " device-stage)]
    ;; device preflight (loud): need rsync to stage and bb to run
    (doseq [c ["rsync" "bb"]]
      (when-not (:ok? (remote-sh? transport (str "command -v " c " >/dev/null")))
        (throw (ex-info (str c " missing on " ip
                             " — install it on the device or reflash a dev image that ships it")
                        {:ip ip :cmd c}))))
    ;; stage the subset (-R recreates the src/ tools/src/ … layout under device-stage). No
    ;; --chmod: preserve source perms so executables (assets/dev/wifi, vendored bb) stay +x.
    ;; root-owned, remote rsync elevated via passwordless sudo.
    (apply sh! :rsync "-aR" "--delete"
           "--chown=root:root"
           "--rsync-path=sudo rsync"
           "-e" ssh-e
           (concat stage-paths [(str host ":" device-stage "/")]))
    (println "staged" (str/join " " stage-paths) "->" (str ip ":" device-stage))
    (println (str "running tools.scripts." script "/run! on " ip))
    (remote-exec! transport remote-cmd)
    {:script script :ip ip :stage device-stage}))


;; What `push <target>` knows how to deploy: the image script that stages it (the copy) and the
;; systemd unit to restart so the new code takes effect. A new deployable target is one entry.
(def ^:private push-targets
  {"agent" {:script "agent" :service "ujima"}})


(defn push!
  "Entry for `dev push <target> <ip>`: stage + run the target's image script (the copy, via
   script!), then restart its systemd unit so the new code is live. Today only \"agent\"
   (tools.scripts.agent -> ujima.service); a new target is one entry in push-targets."
  [{:keys [target ip] :as opts}]
  (if-let [{:keys [script service]} (get push-targets target)]
    (let [result (script! (assoc opts :script script))]
      (println (str "restarting " service " on " ip))
      (remote-exec! (ssh-transport opts) (str "sudo systemctl restart " service))
      (println (str "tail logs:  journalctl -u " service " -f"))
      (assoc result :restarted service))
    (throw (ex-info (str "unknown push target: " target
                         " — supported: " (str/join ", " (keys push-targets)))
                    {:target target}))))
