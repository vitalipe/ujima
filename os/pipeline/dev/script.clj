(ns pipeline.dev.script
  "Runs INSIDE the target chroot as root. Layers DEV-only conveniences onto a configured ujima
   image: an SSH server for headless access, x11vnc + maim + xdotool for the desktop relay
   (`tools dev view` / `dev screenshot` / `dev click|type|key`), the dev kit (os/pipeline/dev/kit ->
   /ujima/dev: wifi, lock-fs, peek, the build-* producers), a tagged shell prompt, and the
   loopback audio rig. Not part of the release pipeline — run on dev images only; nothing
   under os/pipeline/dev/ may ship in a release.

   Pipeline: install -> boot -> base -> ujimad -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [clojure.string :as str]
            [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]
            [build.files :as files]))


(defn run! [{:keys [project]}]
  (with-console-out
    ;; headless dev access: SSH server (raspios ships it present-but-disabled) + rsync, plus the
    ;; desktop-relay tools — x11vnc (interactive `dev view`), maim (one-shot `dev screenshot`), and
    ;; xdotool (synthetic input for `dev click|type|key`). DEV-ONLY by design: a VNC server +
    ;; synthetic-input tooling are remote-control surfaces that must never ship in a release image,
    ;; so they go here (release skips this script), NOT in the install stage.
    ($! apt-get update)
    ($! apt-get install -y --no-install-recommends
        "openssh-server" "rsync" "x11vnc" "maim" "xdotool")
    ($! systemctl enable "ssh")
    ;; Bake host keys into the rootfs now so they stay stable when a dev box runs under the
    ;; read-only overlay (dev-kit lock-fs): sshd then reads them from the ro lower instead of
    ;; regenerating into ephemeral tmpfs every boot (which trips "REMOTE HOST IDENTIFICATION HAS
    ;; CHANGED"). `-A` only fills in missing key types (idempotent); raspios ships none. Release
    ;; skips this script and the cleanup stage wipes any keys, so it's a dev-only concern.
    ($! ssh-keygen -A)

    ;; the on-device dev kit (wifi, lock-fs, peek, build-* producers …): mirrored wholesale,
    ;; so a file removed from the kit disappears from the device on re-runs
    (files/mirror! project "dev/kit" "/ujima/dev")

    ;; dev shell prompt: tag the ujima user's shell so an SSH/console session is obviously
    ;; *this* box. Sourced from ~/.bashrc exactly once (guarded, appended last so it wins
    ;; over the skel PS1); the file itself is a full overwrite, always current.
    (files/install! project "dev/prompt/prompt.sh" "/home/ujima/.ujima-dev.sh")
    (let [bashrc  "/home/ujima/.bashrc"
          srcline "[ -f ~/.ujima-dev.sh ] && . ~/.ujima-dev.sh  # ujima dev prompt"]
      (when-not (and (fs/exists? bashrc)
                     (str/includes? (slurp bashrc) ".ujima-dev.sh"))
        (spit bashrc (str "\n" srcline "\n") :append true)))

    ;; loopback audio sink that classifies as :usb — ujimad's volume path is testable
    ;; without real USB audio (the rig's rationale lives in the files)
    (files/install! project "dev/audio-rig/snd-aloop.conf" "/etc/modules-load.d/snd-aloop.conf")
    (files/install! project "dev/audio-rig/99-ujima-dev-audio-rig.conf"
                    "/etc/wireplumber/wireplumber.conf.d/99-ujima-dev-audio-rig.conf")))
