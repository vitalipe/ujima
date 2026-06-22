(ns ujima.linux.disk
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [ujima.fs :refer [file->number]]
            [lib.shell :refer [$?]]))


(defn- sys-file->path [partition file-name]
  (fs/path "/sys/class/block" (fs/file-name partition) file-name))


(defn- sys-file->long [partition file-name]
  (file->number (sys-file->path partition file-name)))


(defn block-device? [path]
  (:ok? ($? test -b [path])))


(defn partition? [device-path]
  (let [device-name (-> device-path str fs/file-name)]
    (fs/exists? 
      (fs/path "/sys/class/block" 
               device-name 
               "partition"))))


(defn require-block-device! [path]
  (when-not (block-device? path)
    (throw  (ex-info (str path " is not a block device")
                     {:path (str path)})))
  path)


(defn device->partitions [device]
  (->> ($? lsblk -nrpo "NAME" [device])
    (:out)
    (str/split-lines)
    (filter  #(fs/exists? (sys-file->path % "partition")))
    (sort-by #(sys-file->long % "partition"))))


(defn partition->info [path]
  (let [start (sys-file->long  path "start")
        size  (sys-file->long  path "size")]

    {:path  path
     :start-sector start
     :end-sector   (+ start size -1)
     :size-bytes   (* 512 size)}))
         