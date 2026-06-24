(ns tools.scripts.dev
  "Runs INSIDE the target chroot as root. Layers DEV-only conveniences onto a configured ujima
   image: an SSH server for headless access, the assets/dev helper scripts (e.g. `wifi`) staged
   at /ujima/dev, and a [ujima-dev] shell prompt. Not part of the release pipeline — run on dev
   images only.

   Pipeline: install -> base -> agent -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [clojure.string :as str]
            [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]))


;; Bash prompt for the dev image. The stock `ujima@<host>` prompt is easy to confuse with other
;; machines once you've SSH'd into a few boxes; a bold-yellow [ujima-dev] tag makes it clear which
;; one you're on.
(def ^:private dev-prompt
  (str "# ujima dev image prompt (managed by tools.scripts.dev — overwritten each run)\n"
       "PS1='\\[\\e[1;33m\\][ujima-dev]\\[\\e[0m\\] \\u@\\h:\\w\\$ '\n"))


(defn- install-dev-prompt!
  "Idempotent: write the prompt to its own file (full overwrite, always current), and source it
   from the ujima user's ~/.bashrc exactly once (guarded). ~/.bashrc sources it last, so it wins
   over the skel PS1; the `[ -f ]` guard keeps login working even if the file is later removed.
   (base created the ujima user + home before this script runs.) `home` is overridable for
   tests."
  ([] (install-dev-prompt! "/home/ujima"))
  ([home]
   (let [promptf (str home "/.ujima-dev.sh")
         bashrc  (str home "/.bashrc")
         srcline "[ -f ~/.ujima-dev.sh ] && . ~/.ujima-dev.sh  # ujima dev prompt"]
     (spit promptf dev-prompt)
     (when-not (and (fs/exists? bashrc)
                    (str/includes? (slurp bashrc) ".ujima-dev.sh"))
       (spit bashrc (str "\n" srcline "\n") :append true)))))


(defn run! [{:keys [project]}]
  (with-console-out
    ;; SSH server for headless dev access (raspios ships it present-but-disabled)
    ($! apt-get update)
    ($! apt-get install -y --no-install-recommends "openssh-server" "rsync")
    ($! systemctl enable "ssh")

    ;; dev/customization helper scripts (wifi, …): clean-mirror assets/dev -> /ujima/dev (rm
    ;; first so a re-run drops files removed from assets/dev), preserving the exec bit (cp -a).
    (fs/create-dirs "/ujima")
    ($! rm -rf "/ujima/dev")
    ($! cp -a (str project "/assets/dev") "/ujima/")

    ;; dev shell prompt: tag the ujima user's shell so an SSH/console session is obviously
    ;; *this* box, not one of the other machines you're logged into.
    (install-dev-prompt!)))
