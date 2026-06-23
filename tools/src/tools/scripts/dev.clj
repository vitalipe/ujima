(ns tools.scripts.dev
  "Runs INSIDE the target chroot as root. Layers DEV-only conveniences onto a configured ujima
   image: an SSH server for headless access, plus the assets/dev helper scripts (e.g. `wifi`)
   staged at /ujima/dev. Not part of the release pipeline — run on dev images only.

   Pipeline: stage -> stripdown -> configure -> [dev] -> pack -> from-pack.

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]))


(defn run! [{:keys [project]}]
  (with-console-out
    ;; SSH server for headless dev access (raspios ships it present-but-disabled)
    ($! apt-get update)
    ($! apt-get install -y --no-install-recommends "openssh-server" "rsync")
    ($! systemctl enable "ssh")

    ;; dev/customization helper scripts (wifi, …): copy assets/dev -> /ujima/dev,
    ;; preserving the executable bit (cp -a).
    (fs/create-dirs "/ujima")
    ($! cp -a (str project "/assets/dev") "/ujima/")))
