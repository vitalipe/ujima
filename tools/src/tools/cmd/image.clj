(ns tools.cmd.image
  "Host-only os-image machinery: script -> chroot.

   `script` runs an os.<name>/run! namespace inside the target chroot (aarch64 bb
   under qemu) against a read-only project bind, so the script's file ops / shell-outs land in
   the image. Reuses the e2e-tested ujima.device/pack/loopback fns; this namespace is wiring +
   the chroot mechanics. (The A/B disk verbs live in tools.cmd.disk.)"
  (:require
    [clojure.string  :as str]
    [babashka.fs      :as fs]
    [babashka.process :as p]
    [lib.shell              :refer [$! require-root!]]
    [ujima.linux.disk       :as linux-disk]
    [ujima.linux.disk.loop  :as loopback]
    [ujima.linux.disk.mount :as mount]))


;; ---------------------------------------------------------------------------
;; Chroot lifecycle (used by script! and chroot-shell!)
;; ---------------------------------------------------------------------------

;; Vendored host-side binaries (repo-relative). The aarch64 bb runs in place from the
;; read-only project bind; only qemu must be copied, because binfmt resolves it at a fixed
;; path *inside* the chroot.
(def ^:private qemu-src    "assets/tools/qemu-aarch64-static")
(def ^:private qemu-chroot "/usr/bin/qemu-aarch64-static")
(def project-mnt "/ujima-src")  ;; repo bind in the chroot = dev rsync stage (tools.cmd.dev)


;; Base image has 2 partitions [boot root]. Mount root, mount boot at the path the target
;; itself uses, bind kernel fs, bind the repo read-only at project-mnt, inject qemu-static +
;; resolv.conf, run f, then (finally) tear everything down so a no-op run leaves a clean/vanilla
;; rootfs. All binds MUST be unmounted before with-mounted-ext4 unmounts root.
(def boot-mnt "/boot/firmware")  ;; where raspios' fstab puts the boot vfat — NOT /boot


(defn with-chrooted-rootfs* [device f]
  (let [[boot root] (linux-disk/device->partitions device)
        project     (str (fs/cwd))
        binds       ["/dev" "/proc" "/sys"]]
    (mount/with-mounted-ext4 [mnt root]
      (try
        ;; the boot partition is image content too (cmdline.txt, initramfs), so a script sees it
        ;; exactly where a running device does — the same os.<name>/run! then works unchanged
        ;; under `bb dev script`, where /boot/firmware is already mounted.
        (mount/mount! "vfat" boot (str mnt boot-mnt))
        (doseq [b binds]
          ($! mount --bind [b] (str mnt b)))
        ;; repo, read-only: scripts read their source/assets but cannot mutate the tree
        ($! mkdir -p (str mnt project-mnt))
        ($! mount --bind [project] (str mnt project-mnt))
        ($! mount -o "remount,bind,ro" (str mnt project-mnt))
        ;; networking + aarch64 emulation (binfmt resolves qemu at qemu-chroot)
        ($! cp "/etc/resolv.conf"            (str mnt "/etc/resolv.conf"))
        ($! cp (str project "/" qemu-src)  (str mnt qemu-chroot))
        (f mnt)
        (finally
          ;; best-effort teardown: only undo what was actually set up, so a mid-setup
          ;; failure surfaces its own exception instead of being masked by an `umount`
          ;; of something that was never mounted.
          (when (mount/mount-point? (str mnt project-mnt))
            ($! umount (str mnt project-mnt)))
          (when (fs/exists? (str mnt project-mnt))
            ($! rmdir (str mnt project-mnt)))
          (doseq [b (reverse binds)]
            (when (mount/mount-point? (str mnt b))
              ($! umount (str mnt b))))
          ($! rm -f (str mnt qemu-chroot))                ; -f already no-throws on missing
          ($! sh -c (str ": > " mnt "/etc/resolv.conf"))  ; mnt/etc always exists
          ;; boot last: it is nested under root, so it must come off before with-mounted-ext4
          (when (mount/mount-point? (str mnt boot-mnt))
            (mount/umount! (str mnt boot-mnt))))))))


;; ---------------------------------------------------------------------------
;; Image-content scripts
;;
;; A script is os.<name>/run!, executed *inside* the chroot by the
;; vendored aarch64 bb (run in place from the read-only project bind). Add a
;; script by dropping os/src/os/<name>.clj.
;; ---------------------------------------------------------------------------


;; the script set IS the os/src/os dir — one file per script, nothing else. The check
;; fails a typo BEFORE the expensive part: root+loopback here, a full rsync in cmd/dev.
(def ^:private os-scripts-dir "os/src/os")

(defn- available-scripts []
  (->> (fs/glob os-scripts-dir "*.clj")
       (mapv #(str/replace (str (fs/file-name %)) #"\.clj$" ""))
       sort vec))

(defn require-script!
  "Throw (listing what's available) if os.<script> doesn't exist."
  [script]
  (when-not (fs/exists? (fs/path os-scripts-dir (str script ".clj")))
    (throw (ex-info (str "Unknown script: " script)
                    {:script script :available (available-scripts)}))))


(def ^:private chroot-bb   (str project-mnt "/assets/tools/bb-aarch64"))
(def ^:private chroot-cp   (str project-mnt "/runtime/src:" project-mnt "/os/src"))


(defn- do-chroot-run-script! [mnt target]
  (p/shell {:inherit true}
           "sudo" "chroot" (str mnt)
           chroot-bb "--classpath" chroot-cp
           "-x" (str "os." (name target) "/run!")
           "--project" project-mnt))


(defn script!
  "Run a single image-content script (os.<script>/run!) inside the chroot."
  [{:keys [img script]}]
  (require-script! script)
  (require-root!)
  (loopback/with-loopback-device [dev img]
    (with-chrooted-rootfs* dev
      (fn [mnt] (do-chroot-run-script! mnt script)))))


;; The ujima script chain, in order — `bb build` is this plus stage/pack/disk, so the sequence
;; lives here only. boot first: a stash that no longer matches the image's kernel fails in seconds.
(def ^:private content-scripts ["boot" "base" "ujimad" "desktop" "ujimaify"])


(defn build!
  "Run the whole script chain against an existing staged image.
   --dev bakes the dev rig and skips cleanup; the default is a release image."
  [{:keys [img dev]}]
  (require-root!)
  (let [scripts (conj content-scripts (if dev "dev" "cleanup"))]
    (doseq [s scripts]                                  ; whole chain up front, so a typo or a
      (require-script! s))                              ; missing script never runs half of it
    (doseq [s scripts]
      (println (str "== os script " s " -> " img))
      (script! {:img img :script s}))
    {:img (str img) :scripts scripts}))


(defn chroot-shell!
  "Open an interactive root shell inside the image's rootfs (manual-customize entry point)."
  [{:keys [img]}]
  (require-root!)
  (loopback/with-loopback-device [dev img]
    (with-chrooted-rootfs* dev
      (fn [mnt]
        (p/shell {:inherit true} "sudo" "chroot" (str mnt) "/bin/bash")))))
