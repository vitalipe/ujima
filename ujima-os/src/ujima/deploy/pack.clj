(ns ujima.deploy.pack
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [ujima.io :as io]))


(def pack-version 1)


(defn metadata [ujima-pack-path]
  (let [{:keys [ok? out]} (io/sh :tar "--zstd" "-xOf" ujima-pack-path "metadata.edn")]
    (when ok?
      (try
        (edn/read-string out)
        (catch Throwable _ nil)))))


(defn entries [ujima-pack-path]
  (->> ujima-pack-path
       (io/sh! :tar "--zstd" "-tf")
       (:out)
       (str/split-lines)
       (into #{})))


(defn validate! [ujima-pack-path]
  (let [meta     (metadata ujima-pack-path)
        existing (entries ujima-pack-path)]
    
    (doseq [required ["metadata.edn" "boot.img" "root.img"]]
      (when-not (contains? existing required)
        (throw
          (ex-info "Invalid Ujima pack: missing required path"
                   {:pack-path ujima-pack-path
                    :missing required}))))

    (when-not meta
      (throw
        (ex-info "Invalid Ujima pack: unreadable metadata.edn"
                 {:path ujima-pack-path})))

    (when-not (pos? (:pack meta))
      (throw
        (ex-info "Invalid Ujima pack: unreadable :pack version number"
                 {:path ujima-pack-path
                  :pack meta})))

    (when-not (>= pack-version (:pack meta))
      (throw
        (ex-info "Invalid Ujima pack: unsupported newer version"
                 {:path ujima-pack-path
                  :pack meta}))))


  true)


(defn valid? [ujima-pack-path]
  (try
    (validate! ujima-pack-path)
    true
    (catch Throwable _
      false)))


;; FIXME: might the the wrong signature, we probably want to start with a disk image
;;        not 2 partition images
(defn pack!
   ([boot-img root-img ujima-pack-path]
    (pack! boot-img root-img ujima-pack-path {}))
   
   ([boot-img root-img ujima-pack-path pack-metadata]
   
    (io/require-file! boot-img)
    (io/require-file! root-img)

    (fs/with-temp-dir [work-dir {:prefix "ujima-pack-"}]
      (spit (fs/path pack-dir "metadata.edn") (pr-str (assoc pack-metadata :pack pack-version)))

      ;;FIXME: copy to rename, we might be able to tell tar to rename 
      (io/sh! :cp (str boot-img) (str (fs/path work-dir "boot.img")))
      (io/sh! :cp (str root-img) (str (fs/path work-dir "root.img")))

      (io/sh! :tar
              "--zstd"
              "-cf" ujima-pack-path
              "-C" (str work-dir)
              "metadata.edn"
              "boot.img"
              "root.img")

      (validate! ujima-pack-path))))
      

(defn unpack! [ujima-pack-path boot-partition-path root-partition-path]
  (let [shell-quote         (fn [x] (pr-str (str x)))
        write-to-partition! (fn [member partition-path]
                              (io/sh! :bash "-lc"
                                      (str "tar --zstd -xOf "
                                           (shell-quote ujima-pack-path)
                                           " "
                                           (shell-quote member)
                                           " | sudo -n dd of="
                                           (shell-quote partition-path)
                                           " bs=4M conv=fsync status=progress")))]

    (validate! ujima-pack-path)

    (io/require-block-device! boot-partition-path)
    (io/require-block-device! root-partition-path)

    (write-to-partition! "boot.img" boot-partition-path)
    (write-to-partition! "root.img" root-partition-path)

    (io/sudo! :e2fsck "-fy" (str root-partition-path))
    (io/sudo! :resize2fs    (str root-partition-path))
    (io/sudo! :sync)

    nil))