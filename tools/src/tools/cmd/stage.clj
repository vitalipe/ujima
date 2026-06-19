(ns tools.cmd.stage
  "bb tools stage <target>: build a staged, install-scripted image from a pinned base OS.

   fetch (vendor-cached) -> copy to ujima-<branch>-<commit>.img -> image script install."
  (:require
    [clojure.string :as str]
    [babashka.fs :as fs]
    [lib.cli :as cli]
    [ujima.linux.shell :refer [sh! require-root!]]
    [tools.cmd.image :as image]))


;; Pinned base images. Keep entries reproducible: dated URL + sha256 of the .xz.
(def targets
  {"rpi-os"
   {:url    "https://downloads.raspberrypi.com/raspios_lite_arm64/images/raspios_lite_arm64-2026-04-14/2026-04-13-raspios-trixie-arm64-lite.img.xz"
    :sha256 "5c9caff670594eb43b68afee2a156198cb4e4f58e5dec724b4520c53c0ab5aba"}
   ;; "debian" {:url .. :sha256 ..}  ;; TODO: source undecided
   })


(def ^:private vendor-dir "stage/vendor")
(def ^:private stage-dir  "stage")


(defn- git [& args] (apply sh! :git args))


(defn- sanitize [s] (str/replace s #"[^A-Za-z0-9._-]" "-"))


(defn- stage-img-name []
  (let [branch (sanitize (git "rev-parse" "--abbrev-ref" "HEAD"))
        commit (git "rev-parse" "--short" "HEAD")
        dirty  (when-not (str/blank? (git "status" "--porcelain")) "-dev")]
    (str "ujima-" branch "-" commit dirty ".img")))


(defn- vendor-img [url]
  (str (fs/path vendor-dir
                (str/replace (str (fs/file-name url)) #"\.(xz|gz|zip)$" ""))))


(defn stage!
  "fetch (vendor-cached) -> copy to working image -> image script install."
  [target {:keys [no-install]}]
  (let [{:keys [url sha256]} (or (get targets target)
                                 (throw (ex-info (str "Unknown stage target: " target)
                                                 {:target target :available (vec (keys targets))})))
        vendor (vendor-img url)
        out    (str (fs/path stage-dir (stage-img-name)))]

    ;; the chroot install step needs root — fail fast, before the long download
    (when-not no-install
      (require-root!))

    ;; 1. vendor base image (skip if already cached)
    (if (fs/exists? vendor)
      (println "vendor cached ->" vendor)
      (do
        (fs/create-dirs vendor-dir)
        (cli/run-and-display! (image/fetch! {:url url :out vendor :sha256 sha256}))))

    ;; 2. copy to the working image (override)
    (fs/create-dirs stage-dir)
    (println "copy ->" out)
    (fs/copy vendor out {:replace-existing true})

    ;; 3. install image content in the chroot
    (when-not no-install
      (image/script! {:img out :script "install"}))

    (println "staged ->" out)
    {:out out}))


(defn rpi-os! [opts] (stage! "rpi-os" opts))
