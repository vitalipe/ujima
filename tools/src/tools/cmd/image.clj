(ns tools.cmd.image
  "Host-only image build pipeline: fetch -> customize -> pack -> from-pack.

   Skeleton scope: pipeline mechanics only. No image content (packages/copy/fstab/bb) and no
   config reads — `customize` is a no-op chroot. Reuses the e2e-tested ujima.device/pack/loopback
   fns; this namespace is wiring + the chroot/fetch mechanics only."
  (:require
    [clojure.java.io :as io]
    [clojure.string  :as str]
    [babashka.fs      :as fs]
    [babashka.process :as p]
    [lib.task.flow    :refer [flow]]
    [ujima.linux.shell      :refer [$! sudo$! sh! require-root!]]
    [ujima.linux.disk       :as linux-disk]
    [ujima.linux.disk.loop  :as loopback]
    [ujima.linux.disk.mount :as mount]
    [ujima.pack             :as pack]
    [ujima.device.ab        :as ab]
    [ujima.device.ab.autoboot :as ab-auto]))


;; ---------------------------------------------------------------------------
;; Layout registry (tools-side; ujima.device stays untouched)
;; ---------------------------------------------------------------------------

;; image-bytes is a constant per layout (NOT config). 28 GiB fits a real 32 GB card;
;; the A/B `storage` partition fills the remaining space.
(def ^:private layouts
  {"autoboot" {:->disk      ab-auto/->disk
               :image-bytes 30064771072}})


(defn resolve-layout [layout-name]
  (or (get layouts layout-name)
      (throw (ex-info "Unknown --layout"
                      {:expected (set (keys layouts)) :actual layout-name}))))


;; ---------------------------------------------------------------------------
;; Chroot lifecycle (used by customize! and chroot-shell!)
;; ---------------------------------------------------------------------------

;; Vendored host-side binaries (repo-relative). The aarch64 bb runs in place from the
;; read-only project bind; only qemu must be copied, because binfmt resolves it at a fixed
;; path *inside* the chroot.
(def ^:private qemu-src    "assets/tools/qemu-aarch64-static")
(def ^:private qemu-chroot "/usr/bin/qemu-aarch64-static")
(def ^:private project-mnt "/ujima-src")  ;; repo bind-mount point inside the chroot


;; Base image has 2 partitions [boot root]. Mount root, bind kernel fs, bind the repo
;; read-only at project-mnt, inject qemu-static + resolv.conf, run f, then (finally) tear
;; everything down so a no-op run leaves a clean/vanilla rootfs. All binds MUST be unmounted
;; before with-mounted-ext4 unmounts root.
(defn with-chrooted-rootfs* [device f]
  (let [[_boot root] (linux-disk/device->partitions device)
        project      (str (fs/cwd))
        binds        ["/dev" "/proc" "/sys"]]
    (mount/with-mounted-ext4 [mnt root]
      (try
        (doseq [b binds]
          (sudo$! mount --bind [b] [(str mnt b)]))
        ;; repo, read-only: scripts read their source/assets but cannot mutate the tree
        (sudo$! mkdir -p [(str mnt project-mnt)])
        (sudo$! mount --bind [project] [(str mnt project-mnt)])
        (sudo$! mount -o "remount,bind,ro" [(str mnt project-mnt)])
        ;; networking + aarch64 emulation (binfmt resolves qemu at qemu-chroot)
        (sudo$! cp "/etc/resolv.conf"            [(str mnt "/etc/resolv.conf")])
        (sudo$! cp [(str project "/" qemu-src)]  [(str mnt qemu-chroot)])
        (f mnt)
        (finally
          (sudo$! umount [(str mnt project-mnt)])
          (sudo$! rmdir  [(str mnt project-mnt)])
          (doseq [b (reverse binds)]
            (sudo$! umount [(str mnt b)]))
          (sudo$! rm -f [(str mnt qemu-chroot)])
          (sudo$! sh -c [(str ": > " mnt "/etc/resolv.conf")]))))))


;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------


(defn- sha256-of [path]
  (-> (sh! :sha256sum (str path)) (str/split #"\s+") first))


(defn- decompress! [src out]
  (let [s   (str src)
        out (str out)]
    (cond
      (str/ends-with? s ".xz")  (p/shell {:out (io/file out)} "xz"   "-dc" s)
      (str/ends-with? s ".gz")  (p/shell {:out (io/file out)} "gzip" "-dc" s)
      (str/ends-with? s ".zip") (p/shell {:out (io/file out)} "unzip" "-p" s)
      (str/ends-with? s ".img") (fs/copy s out {:replace-existing true})
      :else (throw (ex-info "Unknown image extension (expected .img/.img.xz/.gz/.zip)" {:src s})))))


(defn fetch!
  "Returns a task that downloads `url` -> verifies optional `sha256` (of the compressed
   file) -> decompresses -> `out`."
  [{:keys [url out sha256]}]
  (flow :image/fetch
    (fs/with-temp-dir [dir {:prefix "ujima-fetch-"}]
      (let [dl (str (fs/path dir (fs/file-name url)))]
        (progress! 5 "downloading")
        ($! curl -fsSL --output [dl] [(str url)])
        (progress! 60 "downloaded")
        (when sha256
          (progress! 65 "verifying checksum")
          (let [actual (sha256-of dl)]
            (when-not (= sha256 actual)
              (error! :error/sha256-mismatch (str "sha256 mismatch: expected " sha256 " got " actual)))))
        (progress! 70 "decompressing")
        (decompress! dl out)
        (progress! 100 "done")
        {:out (str out)}))))


;; ---------------------------------------------------------------------------
;; Image-content scripts
;;
;; Each step is tools.scripts.<name>/run!, executed *inside* the chroot by the
;; vendored aarch64 bb (run in place from the read-only project bind). Edit the
;; `scripts` vector to add/remove steps.
;; ---------------------------------------------------------------------------

(def scripts
  "Ordered image-content scripts: the default customize pipeline and the registry
   that backs --only/--from and the 'unknown script' error."
  [:install :configure :cleanup])


(def ^:private chroot-bb (str project-mnt "/assets/tools/bb-aarch64"))
(def ^:private chroot-cp (str project-mnt "/src:" project-mnt "/tools/src"))


(defn- run-script! [mnt target]
  (p/shell {:inherit true}
           "sudo" "chroot" (str mnt)
           chroot-bb "--classpath" chroot-cp
           "-x" (str "tools.scripts." (name target) "/run!")
           "--project" project-mnt))


(defn- select-targets [{:keys [only from]}]
  (let [->known (fn [s]
                  (let [t (keyword s)]
                    (when-not (some #{t} scripts)
                      (throw (ex-info "Unknown script"
                                      {:script s :available (mapv name scripts)})))
                    t))]
    (cond
      only [(->known only)]
      from (subvec scripts (.indexOf scripts (->known from)))
      :else scripts)))


(defn customize!
  "Run the image-content scripts inside the chroot in one session.
   --only <name> runs a single script; --from <name> runs that script onward."
  [{:keys [img] :as opts}]
  (require-root!)
  (let [targets (select-targets opts)]
    (loopback/with-loopback-device [dev img]
      (with-chrooted-rootfs* dev
        (fn [mnt]
          (doseq [t targets]
            (println (str "\n== customize: " (name t) " =="))
            (run-script! mnt t)))))
    (println "customize done ->" (str img))))


(defn apply!
  "Run a single image-content script inside the chroot (ad-hoc entry point)."
  [{:keys [target img]}]
  (customize! {:img img :only target}))


(defn chroot-shell!
  "Open an interactive root shell inside the image's rootfs (manual-customize entry point)."
  [{:keys [img]}]
  (require-root!)
  (loopback/with-loopback-device [dev img]
    (with-chrooted-rootfs* dev
      (fn [mnt]
        (p/shell {:inherit true} "sudo" "chroot" (str mnt) "/bin/bash")))))


(defn from-pack!
  "Returns a task that writes a flashable A/B-layout image from a .pack:
   vanilla rootfs into slot :a, boot slot :a."
  [{pack-path :pack :keys [out layout]}]
  (flow :image/from-pack
    (require-root!)
    (let [{:keys [->disk image-bytes]} (resolve-layout layout)]
      (progress! 5 "validating pack")
      (pack/validate! pack-path)
      (progress! 15 "preparing image")
      (fs/delete-if-exists out)                        ; layout refuses a device that already has partitions
      ($! truncate -s [(str image-bytes)] [(str out)]) ; sparse
      (loopback/with-loopback-device [dev out]
        (let [disk (->disk {:device dev})]
          (progress! 25 "writing A/B layout")
          (ab/write-ujima-layout! disk)
          (progress! 50 "installing slot :a")
          (ab/install-into-slot!  disk pack-path :a)
          (progress! 90 "setting boot slot")
          (ab/set-boot-slot!      disk :a)))
      (progress! 100 "done")
      {:out (str out)})))


(defn run!
  "EXPERIMENTAL stub — qemu boot of the arm64 image is its own project."
  [_]
  (println "image run is experimental — use a separate x86 Debian VM for desktop work."))
