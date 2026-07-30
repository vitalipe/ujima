(ns os.dev
  "Runs INSIDE the target chroot as root. Layers DEV-only conveniences onto a configured ujima
   image: an SSH server for headless access, x11vnc + maim + xdotool for the desktop relay
   (`tools dev view` / `dev screenshot` / `dev click|type|key`), the assets/dev helper scripts
   (e.g. `wifi`) staged at /ujima/dev, and a [ujima-dev] shell prompt (tagged with the overlay
   ro/rw state). Not part of the release pipeline — run on dev images only.

   Pipeline: install -> base -> ujimad -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [clojure.string :as str]
            [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]))


;; Bash prompt for the dev image. The stock `ujima@<host>` prompt is easy to confuse with other
;; machines once you've SSH'd into a few boxes; a bold-yellow [ujima-dev] tag makes it clear which
;; one you're on. It also tags the overlay state — green [ro] (read-only overlay on) vs bold-red
;; [rw] (overlay disabled via lock-fs, writable root) — decided once at shell startup, since the
;; state only changes across a reboot.
(def ^:private dev-prompt
  (str "# ujima dev image prompt (managed by os.dev — overwritten each run)\n"
       "# overlay state, baked in at shell startup (only changes across a reboot):\n"
       "if [ \"$(findmnt -no FSTYPE / 2>/dev/null)\" = overlay ]; then\n"
       "    PS1='\\[\\e[1;33m\\][ujima-dev]\\[\\e[0m\\] \\[\\e[0;32m\\][ro]\\[\\e[0m\\] \\u@\\h:\\w\\$ '\n"
       "else\n"
       "    PS1='\\[\\e[1;33m\\][ujima-dev]\\[\\e[0m\\] \\[\\e[1;31m\\][rw]\\[\\e[0m\\] \\u@\\h:\\w\\$ '\n"
       "fi\n"))


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


;; Audio test rig: snd-aloop is a REAL ALSA sink (honest volume/mute readback, unlike
;; suspended null-sinks), and the rename rule makes it classify as :usb for ujimad
;; (ujima.linux.audio/output-class matches "usb" in node.name) — so the volume glue is
;; testable on a dev box with no USB/HDMI audio attached. Matches the exact aloop node
;; name so the paired capture source stays untouched.
(def ^:private aloop-usb-rule
  (str "# DEV RIG (ujima, managed by os.dev): loopback sink classifies as :usb.\n"
       "monitor.alsa.rules = [\n"
       "  {\n"
       "    matches = [\n"
       "      { node.name = \"alsa_output.platform-snd_aloop.0.analog-stereo\" }\n"
       "    ]\n"
       "    actions = {\n"
       "      update-props = {\n"
       "        node.name        = \"alsa_output.platform-snd_aloop.0.usb-dev-rig\"\n"
       "        node.description = \"Dev USB Rig (aloop)\"\n"
       "      }\n"
       "    }\n"
       "  }\n"
       "]\n"))


(defn- install-audio-rig! []
  (spit "/etc/modules-load.d/snd-aloop.conf" "snd-aloop\n")
  (fs/create-dirs "/etc/wireplumber/wireplumber.conf.d")
  (spit "/etc/wireplumber/wireplumber.conf.d/99-ujima-dev-audio-rig.conf" aloop-usb-rule))


(defn run! [{:keys [project]}]
  (with-console-out
    ;; headless dev access: SSH server (raspios ships it present-but-disabled) + rsync, plus the
    ;; desktop-relay tools — x11vnc (interactive `dev view`), maim (one-shot `dev screenshot`), and
    ;; xdotool (synthetic input for `dev click|type|key`). DEV-ONLY by design: a VNC server +
    ;; synthetic-input tooling are remote-control surfaces that must never ship in a release image,
    ;; so they go here (release skips this script), NOT in os.install.
    ($! apt-get update)
    ($! apt-get install -y --no-install-recommends
        "openssh-server" "rsync" "x11vnc" "maim" "xdotool")
    ($! systemctl enable "ssh")
    ;; Bake host keys into the rootfs now so they stay stable when a dev box runs under the
    ;; read-only overlay (assets/dev/lock-fs): sshd then reads them from the ro lower instead of
    ;; regenerating into ephemeral tmpfs every boot (which trips "REMOTE HOST IDENTIFICATION HAS
    ;; CHANGED"). `-A` only fills in missing key types (idempotent); raspios ships none. Release
    ;; skips this script and os.cleanup wipes any keys, so it's a dev-only concern.
    ($! ssh-keygen -A)

    ;; dev/customization helper scripts (wifi, …): clean-mirror assets/dev -> /ujima/dev (rm
    ;; first so a re-run drops files removed from assets/dev), preserving the exec bit (cp -a).
    (fs/create-dirs "/ujima")
    ($! rm -rf "/ujima/dev")
    ($! cp -a (str project "/assets/dev") "/ujima/")

    ;; dev shell prompt: tag the ujima user's shell so an SSH/console session is obviously
    ;; *this* box, not one of the other machines you're logged into.
    (install-dev-prompt!)

    ;; loopback audio sink that classifies as :usb — ujimad's volume path is testable
    ;; without real USB audio (takes effect after reboot / wireplumber restart).
    (install-audio-rig!)))
