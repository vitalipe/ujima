(ns ujima.pack
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]

            [ujima.fs :refer [slurp-edn spit-edn!]]
            
            [lib.shell              :refer [$ $! $? result-or-fail!]]
            [ujima.linux.sudo       :refer [sudo$ sudo$!]]
            [ujima.linux.disk        :refer [require-block-device! device->partitions]]
            [ujima.linux.disk.mount  :refer [with-mounted-ext4]]))


(defn- unpack-to-partition! [pack-path member partition-path]
  (-> ($ tar --zstd -xOf [pack-path] [member])
      (sudo$ dd {:of partition-path :bs "4M" :conv "fsync"})
      (result-or-fail!)))


(def pack-version 1)
(def base-meta    {:pack-version   pack-version
                   :ujima-version  "0.0.0"
                   :target         :mock
                   :arch           :test})
 

(def system-metadata-path "ujima/system/metadata.edn")
(def system-install-path  "ujima/system/install.edn")


(defn metadata [ujima-pack-path]
  (let [{:keys [ok? out]} ($? tar --zstd -xOf [ujima-pack-path] "metadata.edn")]
    (when ok?
      (try
        (edn/read-string out)
        (catch Throwable _ nil)))))


(defn installed-metadata [root-device]
  (try
    (with-mounted-ext4 [mnt root-device]
      (when (fs/exists? (fs/path mnt system-install-path))
        (when (fs/exists? (fs/path mnt system-metadata-path))
          {:metadata (slurp-edn (fs/path mnt system-metadata-path) {})
           :install  (slurp-edn (fs/path mnt system-install-path)  {})})))
    (catch Exception _ nil)))


(defn entries [ujima-pack-path]
  (->> ($? tar --zstd -tf [ujima-pack-path])
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



(defn pack!
   ([src-device ujima-pack-path]
    (pack! src-device ujima-pack-path {}))
   
   ([src-device ujima-pack-path pack-metadata]
   
    (require-block-device! src-device)

    (let [[boot-src root-src] (device->partitions src-device)]           
      (fs/with-temp-dir [work-dir {:prefix "ujima-pack-"}]
        
        ;; meta
        (spit-edn! (fs/path work-dir "metadata.edn") 
                   (merge base-meta 
                          pack-metadata 
                          {:pack-version pack-version}))

        (sudo$! dd {:if boot-src :of (fs/path work-dir "boot.img") :bs "4M" :conv "fsync"}) 
        (sudo$! dd {:if root-src :of (fs/path work-dir "root.img") :bs "4M" :conv "fsync"}) 
        

        ($! tar --zstd
                -cf [ujima-pack-path]
                -C  [work-dir] "metadata.edn" "boot.img" "root.img")))

    (validate! ujima-pack-path)))
      

(defn unpack! 
  ([ujima-pack-path boot-partition-path root-partition-path]
   (unpack! ujima-pack-path boot-partition-path root-partition-path {}))

  ([ujima-pack-path boot-partition-path root-partition-path install-metadata]
   (validate! ujima-pack-path)

   (require-block-device! boot-partition-path)
   (require-block-device! root-partition-path)
    
   (unpack-to-partition! ujima-pack-path "boot.img" boot-partition-path)
   (unpack-to-partition! ujima-pack-path "root.img" root-partition-path)

   (sudo$! e2fsck    -fn [root-partition-path]) ;; fail on fs errors, don't attempt to fix
   (sudo$! resize2fs -f  [root-partition-path]) ;; -f: we checked it read-only above; skip resize2fs's own "run e2fsck -f first" gate (s_lastcheck<s_mtime after staging)
   (sudo$! sync)

   ;;install metadata
   (with-mounted-ext4 [mnt-root root-partition-path]
     (fs/with-temp-dir [tmp-dir {:prefix "ujima-install-manifest-"}]
       (let [pack-meta    (metadata ujima-pack-path)
             install-meta (merge install-metadata {:installed-at (str (java.time.Instant/now))})]
         
         (spit-edn! (fs/path tmp-dir "meta.edn")    pack-meta)
         (spit-edn! (fs/path tmp-dir "install.edn") install-meta)

         (sudo$! install -D  -m "0644" (fs/path tmp-dir "meta.edn")    (fs/path mnt-root system-metadata-path))
         (sudo$! install -D  -m "0644" (fs/path tmp-dir "install.edn") (fs/path mnt-root system-install-path)))))
          
   nil))