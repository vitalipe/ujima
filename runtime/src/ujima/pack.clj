(ns ujima.pack
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]

            [lib.io :refer [slurp-edn spit-edn!]]
            
            [lib.shell              :refer [$ $! $? result-or-fail!]]
            [ujima.linux.sudo       :refer [sudo$ sudo$!]]
            [ujima.linux.disk        :refer [require-block-device! device->partitions]]
            [ujima.linux.disk.mount  :refer [with-mounted-ext4]]))


(defn- unpack-to-partition! [pack-path member partition-path]
  (-> ($ tar --zstd -xOf [pack-path] [member])
      (sudo$ dd {:of partition-path :bs "4M" :conv "fsync"})
      (result-or-fail!)))


(def pack-version 1)
(def manifest-member "manifest.edn")
(def installed-metadata-path "ujima/system/pack.edn")


(defn manifest [ujima-pack-path]
  (let [{:keys [ok? out]} ($? tar --zstd -xOf [ujima-pack-path] [manifest-member])]
    (when ok?
      (try
        (edn/read-string out)
        (catch Throwable _ nil)))))


(defn- sha256-of [path]
  (-> ($! sha256sum [path]) (str/split #"\s+") first))


(defn member-entry
  "Manifest entry for a member file on disk."
  [file path]
  {:file   file
   :sha256 (sha256-of path)
   :bytes  (fs/size path)})


(defn installed-metadata [root-device]
  (try
    (with-mounted-ext4 [mnt root-device]
      (when (fs/exists? (fs/path mnt installed-metadata-path))
        (slurp-edn (fs/path mnt installed-metadata-path) {})))
    (catch Exception _ nil)))


(defn entries [ujima-pack-path]
  (->> ($? tar --zstd -tf [ujima-pack-path])
       (:out)
       (str/split-lines)
       (into #{})))


(defn validate! [ujima-pack-path]
  (let [mf       (manifest ujima-pack-path)
        existing (entries ujima-pack-path)]

    (doseq [required [manifest-member "boot.img" "root.img"]]
      (when-not (contains? existing required)
        (throw
          (ex-info "Invalid Ujima pack: missing required path"
                   {:pack-path ujima-pack-path
                    :missing required}))))

    (when-not mf
      (throw
        (ex-info "Invalid Ujima pack: unreadable manifest.edn"
                 {:path ujima-pack-path})))


    (when-not (int? (:pack-version mf))
      (throw
        (ex-info "Invalid Ujima pack: unreadable :pack version number"
                 {:path ujima-pack-path
                  :pack mf})))


    (when-not (>= pack-version (:pack-version mf))
      (throw
        (ex-info "Invalid Ujima pack: unsupported newer version"
                 {:path ujima-pack-path
                  :pack mf})))


    (when-not (map? (:image mf))
      (throw
        (ex-info "Invalid Ujima pack: manifest carries no :image facts"
                 {:path ujima-pack-path
                  :pack mf})))


    (doseq [member [:boot :root]]
      (let [{:keys [sha256 bytes]} (get-in mf [:members member])]
        (when-not (and (string? sha256) (int? bytes))
          (throw
            (ex-info "Invalid Ujima pack: manifest member without sha256/bytes"
                     {:path ujima-pack-path
                      :member member
                      :pack mf})))))
    true))


(defn valid? [ujima-pack-path]
  (try
    (validate! ujima-pack-path)
    true (catch Throwable _ false)))
 

(defn pack!
  [src-device ujima-pack-path]

  (require-block-device! src-device)
  
  (let [[boot-src root-src] (device->partitions src-device)
        image-edn (with-mounted-ext4 [mnt root-src]
                    (slurp-edn (fs/path mnt "ujima/image.edn")))]

    (when-not image-edn
      (throw (ex-info "Source has no valid /ujima/image.edn — not a stamped image"
                      {:device (str src-device)})))


    (fs/with-temp-dir [work-dir {:prefix "ujima-pack-"}]

      (sudo$! dd {:if boot-src :of (fs/path work-dir "boot.img") :bs "4M" :conv "fsync"})
      (sudo$! dd {:if root-src :of (fs/path work-dir "root.img") :bs "4M" :conv "fsync"})

      (spit-edn! (fs/path work-dir manifest-member)
                 {:pack-version pack-version
                  :packed-at    (str (java.time.Instant/now))
                  :image        image-edn
                  :members      {:boot (member-entry "boot.img" (fs/path work-dir "boot.img"))
                                 :root (member-entry "root.img" (fs/path work-dir "root.img"))}})

      ($! tar --zstd
              -cf [ujima-pack-path]
              -C  [work-dir] [manifest-member] "boot.img" "root.img")))

  (validate! ujima-pack-path))


(defn unpack!
  [ujima-pack-path boot-partition-path root-partition-path]
  (validate! ujima-pack-path)

  (require-block-device! boot-partition-path)
  (require-block-device! root-partition-path)

  (unpack-to-partition! ujima-pack-path "boot.img" boot-partition-path)
  (unpack-to-partition! ujima-pack-path "root.img" root-partition-path)

  (sudo$! e2fsck    -fn [root-partition-path]) ;; fail on fs errors, don't attempt to fix
  (sudo$! resize2fs -f  [root-partition-path]) ;; -f: we checked it read-only above; skip resize2fs's own "run e2fsck -f first" gate (s_lastcheck<s_mtime after staging)
  (sudo$! sync)

  (with-mounted-ext4 [mnt-root root-partition-path]
    (fs/with-temp-dir [tmp-dir {:prefix "ujima-install-manifest-"}]
      (let [installed (assoc (manifest ujima-pack-path)
                             :installed-at (str (java.time.Instant/now)))]
        (spit-edn! (fs/path tmp-dir "pack.edn") installed)
        (sudo$! install -D -m "0644"
                (fs/path tmp-dir "pack.edn")
                (fs/path mnt-root installed-metadata-path)))))

  nil)