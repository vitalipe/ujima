(ns ujima.deploy.pack
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]

            [ujima.fs :refer [require-file!]]
            
            [ujima.linux.shell     :refer [$ sudo$ $! sudo$! sh! sh sudo! result-or-fail!]]
            [ujima.linux.disk      :refer [require-block-device! device->partitions]]
            [ujima.linux.disk.loop :refer [with-loopback-device]]))


(def pack-version 1)
(def base-meta    {:pack-version   pack-version
                   :ujima-version  "0.0.0"
                   :target         :mock
                   :arch           :test})
 

(defn metadata [ujima-pack-path]
  (let [{:keys [ok? out]} (sh :tar "--zstd" "-xOf" ujima-pack-path "metadata.edn")]
    (when ok?
      (try
        (edn/read-string out)
        (catch Throwable _ nil)))))


(defn entries [ujima-pack-path]
  (->> ujima-pack-path
       (sh :tar "--zstd" "-tf")
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


    (when-not (int? (:pack-version meta))
      (throw
        (ex-info "Invalid Ujima pack: unreadable :pack version number"
                 {:path ujima-pack-path
                  :pack meta})))


    (when-not (>= pack-version (:pack-version meta))
      (throw
        (ex-info "Invalid Ujima pack: unsupported newer version"
                 {:path ujima-pack-path
                  :pack meta})))
    true))


(defn valid? [ujima-pack-path]
  (try
    (validate! ujima-pack-path)
    true (catch Throwable _ false)))


(defn- pack-to-file! [src dst]
  (sudo! :dd (str "if=" src) 
             (str "of=" dst)
             "bs=4M"
             "conv=fsync"))


(defn- unpack-to-partition! [pack-path member partition-path]
  (-> ($ tar --zstd -xOf [pack-path] [member])
      (sudo$ dd [(str "of="  partition-path)] "bs=4M" "conv=fsync")
      (result-or-fail!)))


(defn pack!
   ([src-device ujima-pack-path]
    (pack! src-device ujima-pack-path {}))
   
   ([src-device ujima-pack-path pack-metadata]
   
    (require-block-device! src-device)

    (let [[boot-src root-src] (device->partitions src-device)]           
      (fs/with-temp-dir [work-dir {:prefix "ujima-pack-"}]
        
        ;; meta
        (spit (str (fs/path work-dir "metadata.edn")) 
              (pr-str (merge base-meta 
                             pack-metadata 
                             {:pack-version pack-version})))

        (pack-to-file! boot-src (fs/path work-dir "boot.img"))
        (pack-to-file! root-src (fs/path work-dir "root.img"))

        ($! tar --zstd
                -cf [ujima-pack-path]
                -C  [work-dir] "metadata.edn" "boot.img" "root.img")))

    (validate! ujima-pack-path)))
      

(defn unpack! [ujima-pack-path boot-partition-path root-partition-path]
  (validate! ujima-pack-path)

  (require-block-device! boot-partition-path)
  (require-block-device! root-partition-path)
    
  (unpack-to-partition! ujima-pack-path "boot.img" boot-partition-path)
  (unpack-to-partition! ujima-pack-path "root.img" root-partition-path)

  (sudo$! e2fsck -fy [root-partition-path])
  (sudo$! resize2fs  [root-partition-path])
  (sudo$! sync)

  nil)