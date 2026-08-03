(ns os.boot
  "Runs INSIDE the target chroot as root. Owns the boot partition: the overlayroot cmdline and the
   prebuilt initramfs that implements it.

   The initramfs can't be generated here — update-initramfs segfaults under qemu — so the image
   ships a kernel-matched one from assets/initramfs, produced on a Pi by assets/dev/build-initramfs.

   Pipeline: install -> boot -> base -> ujimad -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [lib.shell :refer [$! with-console-out]]
            [ujima.device.ab.autoboot.bootfiles :as bootfiles]))


(def ^:private boot-firmware   "/boot/firmware")
(def ^:private initramfs-src   "boot/initramfs")   ;; the concern dir under os/
(def ^:private initramfs-files ["initramfs8" "initramfs_2712"])


;; recurse=0 so the fstab submounts punch through instead of being overlaid too. No `rw` — the
;; tmpfs upper is the write layer. No fsck.repair — the ro lower can't corrupt. `root` is the one
;; token we don't pick: it stays whatever the image points at, and the installer re-points it.
(defn- ujima-cmdline [root]
  [["overlayroot" "tmpfs:recurse=0"]
   ["console"     "serial0,115200"]
   ["console"     "tty1"]
   ["root"        root]
   ["rootfstype"  "ext4"]
   ["rootwait"    nil]])


(defn- write-cmdline! []
  (let [root (or (bootfiles/cmdline-get (bootfiles/cmdline boot-firmware) "root")
                 (throw (ex-info "cmdline.txt has no root= — refusing to guess the rootfs"
                                 {:path (str boot-firmware "/cmdline.txt")})))]
    (println "cmdline root=" root)
    (bootfiles/cmdline! boot-firmware (ujima-cmdline root))))


(defn- kernel-version
  "Base version shared by the installed rpi kernels, e.g. \"6.12.75+rpt\"."
  []
  (let [bases (->> (fs/list-dir "/lib/modules")
                   (map (comp str fs/file-name))
                   (filter #(str/includes? % "+rpt"))
                   (map #(str/replace % #"-rpi-.*$" ""))
                   distinct)]
    (when-not (= 1 (count bases))
      (throw (ex-info "could not derive a single rpi kernel base version from /lib/modules"
                      {:found bases})))
    (first bases)))


;; keyed by kernel version and hard-failing on a miss, so a base bump that outdates the stash stops
;; the build loudly instead of shipping a silently overlay-less image
(defn- bake-initramfs! [project]
  (let [version (kernel-version)
        src     (str project "/os/" initramfs-src "/" version)]

    (doseq [f initramfs-files]
      (when-not (fs/exists? (str src "/" f))
        (throw (ex-info (str "no prebuilt initramfs for kernel " version
                             " — regenerate with the build-initramfs producer (dev kit) on a Pi "
                             "and copy into os/" initramfs-src "/" version)
                        {:version version :missing (str src "/" f)}))))

    (doseq [f initramfs-files]
      ($! cp (str src "/" f) (str boot-firmware "/" f)))

    (println "baked prebuilt initramfs" version)))


(defn run! [{:keys [project]}]
  (with-console-out
    (bake-initramfs! project)
    (write-cmdline!)))
