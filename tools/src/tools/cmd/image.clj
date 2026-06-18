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

(def ^:private qemu-aarch64-static "/usr/bin/qemu-aarch64-static")


;; Base image has 2 partitions [boot root]. Mount root, bind kernel fs, inject qemu-static +
;; resolv.conf, run f, then (finally) unbind and remove the injected files so a no-op run leaves
;; a clean/vanilla rootfs. The unbind MUST happen before with-mounted-ext4 unmounts root.
(defn with-chrooted-rootfs* [device f]
  (let [[_boot root] (linux-disk/device->partitions device)
        binds        ["/dev" "/proc" "/sys"]]
    (mount/with-mounted-ext4 [mnt root]
      (try
        (doseq [b binds]
          (sudo$! mount --bind [b] [(str mnt b)]))
        (sudo$! cp "/etc/resolv.conf"    [(str mnt "/etc/resolv.conf")])
        (sudo$! cp [qemu-aarch64-static] [(str mnt qemu-aarch64-static)])
        (f mnt)
        (finally
          (doseq [b (reverse binds)]
            (sudo$! umount [(str mnt b)]))
          (sudo$! rm -f [(str mnt qemu-aarch64-static)])
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


(defn customize!
  "No-op chroot skeleton: set up the chroot and apply nothing (vanilla rootfs out).
   Content (packages/copy/fstab/bb/hardening) is added by hand later."
  [{:keys [img]}]
  (require-root!)
  (loopback/with-loopback-device [dev img]
    (with-chrooted-rootfs* dev (fn [_mnt] nil)))
  (println "customize (no-op skeleton) ->" (str img)))


(defn chroot-shell!
  "Open an interactive root shell inside the image's rootfs (manual-customize entry point)."
  [{:keys [img]}]
  (require-root!)
  (loopback/with-loopback-device [dev img]
    (with-chrooted-rootfs* dev
      (fn [mnt]
        (p/shell {:inherit true} "sudo" "chroot" (str mnt) "/bin/bash")))))


(defn from-pack!
  "Write a flashable A/B-layout image from a .pack: vanilla rootfs into slot :a, boot slot :a."
  [{pack-path :pack :keys [out layout]}]
  (require-root!)
  (let [{:keys [->disk image-bytes]} (resolve-layout layout)]
    (pack/validate! pack-path)
    (fs/delete-if-exists out)                       ; layout refuses a device that already has partitions
    ($! truncate -s [(str image-bytes)] [(str out)]) ; sparse
    (loopback/with-loopback-device [dev out]
      (let [disk (->disk {:device dev})]
        (ab/write-ujima-layout! disk)
        (ab/install-into-slot!  disk pack-path :a)
        (ab/set-boot-slot!      disk :a)))
    (println "wrote A/B image ->" (str out))))


(defn run!
  "EXPERIMENTAL stub — qemu boot of the arm64 image is its own project."
  [_]
  (println "image run is experimental — use a separate x86 Debian VM for desktop work."))
