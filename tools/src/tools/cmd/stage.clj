(ns tools.cmd.stage
  "bb stage <target>: build a staged image from a pinned base OS.

   Vendor base (fetch + `os script install`, cached under stage/vendor/) -> copy to
   stage/ujima-<branch>-<commit>.img. The vendor is built once; rm it to rebuild
   (e.g. after editing os.install or bumping the vendored bb). Fetch lives here because
   the vendor build is its only caller."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [babashka.fs :as fs]
    [babashka.process :as p]
    [lib.cli :as cli]
    [lib.task.flow :refer [flow]]
    [lib.shell :refer [sh! require-root! $!]]
    [ujima.linux.disk :as linux-disk]
    [ujima.linux.disk.loop :as loopback]
    [tools.cmd.os :as os]))


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


(defn- fetch!
  "Returns a task that downloads `url` -> verifies optional `sha256` (of the compressed
   file) -> decompresses -> `out`."
  [{:keys [url out sha256]}]
  (flow :stage/fetch
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


;; Pinned base images. Keep entries reproducible: dated URL + sha256 of the .xz.
(def targets
  {"rpi-os"
   {:url    "https://downloads.raspberrypi.com/raspios_lite_arm64/images/raspios_lite_arm64-2026-04-14/2026-04-13-raspios-trixie-arm64-lite.img.xz"
    :sha256 "5c9caff670594eb43b68afee2a156198cb4e4f58e5dec724b4520c53c0ab5aba"}
   ;; "debian" {:url .. :sha256 ..}  ;; TODO: source undecided
   })


(def ^:private vendor-dir "stage/vendor")
(def ^:private stage-dir  "stage")


(declare expand-root!)


(defn- git [& args] (apply sh! :git args))


(defn- sanitize [s] (str/replace s #"[^A-Za-z0-9._-]" "-"))


(defn- stage-img-name []
  (let [branch (sanitize (git "rev-parse" "--abbrev-ref" "HEAD"))
        commit (git "rev-parse" "--short" "HEAD")
        dirty  (when-not (str/blank? (git "status" "--porcelain" "--untracked-files=no")) "-dirty")]
    (str "ujima-" branch "-" commit dirty ".img")))


(defn- vendor-img [url]
  (str (fs/path vendor-dir
                (str/replace (str (fs/file-name url)) #"\.(xz|gz|zip)$" ""))))


(defn- build-vendor!
  "Fetch the base image and bake `install` (packages + bb) into it, then publish it
   to the cached vendor path. Built in a temp file and moved into place only on
   success, so a failed install never leaves a poisoned cache."
  [url sha256 vendor]
  (require-root!)                       ; chroot install needs root — fail before the download
  (fs/create-dirs vendor-dir)
  (let [tmp (str vendor ".building")]
    (fs/delete-if-exists tmp)
    (cli/run-and-display! (fetch! {:url url :out tmp :sha256 sha256}))
    ;; grow the rootfs BEFORE the install script runs: the app packages (libreoffice, chromium,
    ;; inkscape, …) and the fetched TurboWarp payload install into THIS image, and the stock
    ;; raspios root (~2.4G) can't hold them. HARD CAP at the 10GiB A/B root slot
    ;; (ujima.device.ab.autoboot.partitions, MiB 10240): `pack` dd's this partition RAW into
    ;; root.img and `from-pack` writes it into that slot — anything bigger overflows it (broken
    ;; pipe). 10G here = a ~9.5GiB root partition (the image minus the ~512MiB boot), which fits
    ;; the slot with margin; the rootfs only uses ~4.4G so there's ample install headroom. A
    ;; fresh decompress needs no e2fsck gate (-f).
    (expand-root! tmp "10G")
    (os/script! {:img tmp :script "install"})
    (fs/move tmp vendor {:replace-existing true})))


(defn- expand-root!
  "Grow the staged image + its root partition (raspios p2) to `size` so the chroot scripts have
   room — the stock raspios root is ~2.4G and fills once the desktop packages (i3/X/GTK) land.
   resize2fs is command-remapped (config/tools.local.edn) to a trixie-capable e2fsprogs; the host's
   own is too old for trixie's ext4. -f skips the e2fsck gate (fresh copy of the cached vendor)."
  [img size]
  ($! truncate -s [size] [img])
  (loopback/with-loopback-device [dev img]
    (let [root (last (linux-disk/device->partitions dev))]
      ($! parted -s [dev] "resizepart" "2" "100%")
      ($! partprobe [dev])
      ($! resize2fs -f [root]))))


(defn stage!
  "Build a cached vendor base (base OS + packages + bb) and copy it to a working
   image. The vendor is built once; rm stage/vendor/<name>.img to rebuild it."
  [target _opts]
  (let [{:keys [url sha256]} (or (get targets target)
                                 (throw (ex-info (str "Unknown stage target: " target)
                                                 {:target target :available (vec (keys targets))})))
        vendor (vendor-img url)
        out    (str (fs/path stage-dir (stage-img-name)))]
    (require-root!)

    ;; 1. vendor base (base + install), built once and cached
    (if (fs/exists? vendor)
      (println "vendor cached ->" vendor)
      (build-vendor! url sha256 vendor))

    ;; 2. copy to the working image — sparse (the vendor is a 10G rootfs pre-grown in build-vendor!,
    ;; so no post-copy expand is needed; --sparse=always keeps the copy from inflating the holes).
    (fs/create-dirs stage-dir)
    (println "copy ->" out)
    ($! cp --sparse=always [vendor] [out])

    (println "staged ->" out "(rm" vendor "to rebuild the base)")
    {:out out}))


(defn rpi-os! [opts] (stage! "rpi-os" opts))
