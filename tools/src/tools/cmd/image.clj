(ns tools.cmd.image
  "Host-only image build pipeline: fetch -> script -> pack -> from-pack.

   `script` runs a tools.scripts.<name>/run! namespace inside the target chroot (aarch64 bb
   under qemu) against a read-only project bind, so the script's file ops / shell-outs land in
   the image. Reuses the e2e-tested ujima.device/pack/loopback fns; this namespace is wiring +
   the chroot/fetch mechanics."
  (:require
    [clojure.java.io :as io]
    [clojure.string  :as str]
    [babashka.fs      :as fs]
    [babashka.process :as p]
    [lib.task.flow    :refer [flow]]
    [lib.shell              :refer [$! sh! require-root!]]
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
;; Chroot lifecycle (used by script! and chroot-shell!)
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
          ($! umount (str mnt project-mnt))
          ($! rmdir  (str mnt project-mnt))
          (doseq [b (reverse binds)]
            ($! umount (str mnt b)))
          ($! rm -f (str mnt qemu-chroot))
          ($! sh -c (str ": > " mnt "/etc/resolv.conf")))))))


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
        ($! curl -fsSL --output [dl] (str url))
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
;; A script is tools.scripts.<name>/run!, executed *inside* the chroot by the
;; vendored aarch64 bb (run in place from the read-only project bind). Add a
;; script by dropping tools/src/tools/scripts/<name>.clj.
;; ---------------------------------------------------------------------------


(def ^:private chroot-bb   (str project-mnt "/assets/tools/bb-aarch64"))
(def ^:private chroot-cp   (str project-mnt "/src:" project-mnt "/tools/src"))
(def ^:private scripts-dir "tools/src/tools/scripts")  ;; host-side, repo-relative


(defn- do-chroot-run-script! [mnt target]
  (p/shell {:inherit true}
           "sudo" "chroot" (str mnt)
           chroot-bb "--classpath" chroot-cp
           "-x" (str "tools.scripts." (name target) "/run!")
           "--project" project-mnt))


(defn- require-script!
  "Fail fast (before any chroot/loopback work) if tools.scripts.<script> doesn't exist."
  [script]
  (when-not (fs/exists? (fs/path scripts-dir (str script ".clj")))
    (let [available (->> (fs/glob scripts-dir "*.clj")
                         (mapv #(str/replace (str (fs/file-name %)) #"\.clj$" ""))
                         sort vec)]
      (throw (ex-info (str "Unknown script: " script)
                      {:script script :available available})))))


(defn script!
  "Run a single image-content script (tools.scripts.<script>/run!) inside the chroot."
  [{:keys [img script]}]
  (require-script! script)
  (require-root!)
  (loopback/with-loopback-device [dev img]
    (with-chrooted-rootfs* dev
      (fn [mnt] (do-chroot-run-script! mnt script)))))


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
      ($! truncate -s (str image-bytes) (str out)) ; sparse
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
