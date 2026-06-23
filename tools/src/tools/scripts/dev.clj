(ns tools.scripts.dev
  "Runs INSIDE the target chroot as root. Layers DEV-only conveniences onto a configured ujima
   image: an SSH server for headless access, the assets/dev helper scripts (e.g. `wifi`) staged
   at /ujima/dev, and a [ujima-dev] shell prompt. Not part of the release pipeline — run on dev
   images only.

   Pipeline: stage -> stripdown -> configure -> [dev] -> pack -> from-pack.

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]))


;; Bash prompt override for the dev image. The stock `ujima@<host>` prompt is easy to confuse
;; with other machines once you've SSH'd into a few boxes; a bold-yellow [ujima-dev] tag makes
;; it unmistakable which one you're on. Appended to the ujima user's ~/.bashrc — that runs last
;; for interactive shells (SSH + console), so it wins over the skel PS1. (configure created the
;; user + home before this script runs.)
(def ^:private dev-bashrc
  (str "\n# ujima dev image — make it obvious which box you're on (tools.scripts.dev)\n"
       "PS1='\\[\\e[1;33m\\][ujima-dev]\\[\\e[0m\\] \\u@\\h:\\w\\$ '\n"))


(defn run! [{:keys [project]}]
  (with-console-out
    ;; SSH server for headless dev access (raspios ships it present-but-disabled)
    ($! apt-get update)
    ($! apt-get install -y --no-install-recommends "openssh-server" "rsync")
    ($! systemctl enable "ssh")

    ;; dev/customization helper scripts (wifi, …): copy assets/dev -> /ujima/dev,
    ;; preserving the executable bit (cp -a).
    (fs/create-dirs "/ujima")
    ($! cp -a (str project "/assets/dev") "/ujima/")

    ;; dev shell prompt: tag the ujima user's shell so an SSH/console session is obviously
    ;; *this* box, not one of the other machines you're logged into.
    (spit "/home/ujima/.bashrc" dev-bashrc :append true)))
