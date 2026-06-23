(ns tools.scripts.configure
  "Runs INSIDE the target chroot as root. Builds the ujima desktop on top of the stripped,
   bootable base produced by tools.scripts.stripdown: stages the ujima agent + deployment
   config and provisions the login user (passwordless console autologin + passwordless sudo).

   Pipeline: stage -> stripdown -> configure -> pack -> from-pack.

   `project` is the read-only repo bind inside the chroot (default /ujima-src).

   TODO (not done yet):
     - the actual desktop: packages + graphical session (console autologin is done below)
     - systemd unit to launch ujima.core/-main on boot"
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


(defn- grant-passwordless-sudo!
  "Let ujima run sudo without a password prompt. Dropped into /etc/sudoers.d (sudo @includedir's
   the dir); the file MUST be 0440 + root-owned or sudo silently ignores it. `visudo -c` fails
   the build loudly on a malformed rule rather than shipping an image whose sudo is broken."
  []
  (let [f "/etc/sudoers.d/ujima-nopasswd"]
    (spit f "ujima ALL=(ALL) NOPASSWD: ALL\n")
    ($! chmod "0440" [f])
    ($! visudo -c -f [f])))


(defn- enable-console-autologin!
  "Log ujima straight into a tty1 console on boot — no password prompt. This is the Lite/console
   form of 'logged in'; the eventual desktop session supersedes it. Canonical systemd drop-in
   (the same one raspi-config writes): the empty ExecStart clears the unit's inherited command,
   then agetty --autologin replaces it. Nothing ships an autologin drop-in on the stripped base
   (cloud-init + userconfig are off), so this is the only one."
  []
  (let [dir "/etc/systemd/system/getty@tty1.service.d"]
    (fs/create-dirs dir)
    (spit (str dir "/autologin.conf")
          (str "[Service]\n"
               "ExecStart=\n"
               "ExecStart=-/sbin/agetty --autologin ujima --noclear %I $TERM\n"))))


(defn run! [{:keys [project]}]
  (with-console-out
    (let [dst "/opt/ujima"]
      (fs/create-dirs (str dst "/config"))

      ;; agent source + deployment config
      ($! cp -a (str project "/src")             (str dst "/"))
      ($! cp -a (str project "/config/ujima.edn") (str dst "/config/"))

      ;; login user (stripdown turned cloud-init off, so nothing else makes one)
      (create-login-user!)
      (grant-passwordless-sudo!)
      (enable-console-autologin!)

      ;; release marker
      (spit "/etc/ujima-release"
            (str "built-at=" (java.time.Instant/now) "\n")))))
