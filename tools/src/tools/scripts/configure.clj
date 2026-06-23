(ns tools.scripts.configure
  "Runs INSIDE the target chroot as root. Builds the ujima desktop on top of the stripped,
   bootable base produced by tools.scripts.stripdown: stages the ujima agent + deployment
   config and provisions the login user.

   Pipeline: stage -> stripdown -> configure -> pack -> from-pack.

   `project` is the read-only repo bind inside the chroot (default /ujima-src).

   TODO (not done yet):
     - the actual desktop: packages, session, autologin
     - systemd unit to launch ujima.core/-main on boot
     - real user/credentials from config instead of the default ujima/ujima below"
  (:require [lib.shell :refer [$ $! $? with-console-out]]
            [babashka.fs :as fs]))


(defn- create-login-user!
  "stripdown disables cloud-init (which would have created the login user from
   /boot/firmware/user-data), so create one here. The default ujima/ujima credential is
   intentional: ujima is a public-access machine where physical access already implies root,
   so the login password isn't a secret (drive it from config if that ever changes)."
  []
  (when-not (:ok? ($? id "ujima"))
    ($! useradd -m -s "/bin/bash" -G "sudo" "ujima"))
  (-> ($ echo "ujima:ujima") ($! chpasswd)))


(defn run! [{:keys [project]}]
  (with-console-out
    (let [dst "/opt/ujima"]
      (fs/create-dirs (str dst "/config"))

      ;; agent source + deployment config
      ($! cp -a (str project "/src")             (str dst "/"))
      ($! cp -a (str project "/config/ujima.edn") (str dst "/config/"))

      ;; login user (stripdown turned cloud-init off, so nothing else makes one)
      (create-login-user!)

      ;; release marker
      (spit "/etc/ujima-release"
            (str "built-at=" (java.time.Instant/now) "\n")))))
