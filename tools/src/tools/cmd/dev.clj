(ns tools.cmd.dev
  "Host-side dev-loop commands against a RUNNING ujima dev device over ssh. Distinct from
   tools.scripts.dev (the in-chroot build script). `push agent` rsyncs the local working-tree
   src/ into /opt/ujima/src on the device."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [lib.shell :refer [sh! sh?]]))


(defn- require-host-cmd! [cmd hint]
  (when-not (fs/which cmd)
    (throw (ex-info (str cmd " not found on host — " hint) {:cmd cmd}))))


(defn- push-agent!
  "rsync the local agent source (src/) onto a running dev device at /opt/ujima/src.
   Mirror (--delete), owned root:root (dirs 0755 / files 0644), remote rsync under sudo."
  [{:keys [ip user password port]}]
  (require-host-cmd! "sshpass" "install it (e.g. apt install sshpass)")
  (require-host-cmd! "rsync"   "install it (e.g. apt install rsync)")
  (let [ssh-opts ["-p" port "-o" "StrictHostKeyChecking=no" "-o" "UserKnownHostsFile=/dev/null"]
        ssh-e    (str "sshpass -p " password " ssh " (str/join " " ssh-opts))
        host     (str user "@" ip)
        dest     (str host ":/opt/ujima/src/")]
    ;; device preflight (loud): rsync must be present, since the dev image may predate it
    (when-not (:ok? (apply sh? :sshpass "-p" password "ssh"
                           (concat ssh-opts [host "command -v rsync >/dev/null"])))
      (throw (ex-info (str "rsync missing on " ip
                           " — `sudo apt install rsync` on the device, or reflash a dev image that ships it")
                      {:ip ip})))
    ;; mirror src/ -> /opt/ujima/src/, root-owned, remote rsync elevated via passwordless sudo
    (sh! :rsync "-a" "--delete"
         "--chown=root:root" "--chmod=D755,F644"
         "--rsync-path=sudo rsync"
         "-e" ssh-e
         "src/" dest)
    (println "pushed src/ ->" dest)
    (println "run on device:  cd /opt/ujima && bb -cp src -m ujima.core")
    {:pushed dest}))


(defn push!
  "Entry for `dev push <target> <ip>`. Only target \"agent\" today (rsync src/ -> /opt/ujima/src);
   leaves room for `dev push config`/`assets` siblings later."
  [{:keys [target] :as opts}]
  (case target
    "agent" (push-agent! opts)
    (throw (ex-info (str "unknown push target: " target " — supported: agent") {:target target}))))
