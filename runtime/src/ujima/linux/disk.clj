(ns ujima.linux.disk
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [lib.io :refer [file->number]]
            [lib.shell :refer [$?]]
            [ujima.linux.disk.mount :as mount]))


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


(defn partuuid->disk
  "The whole-disk device holding PARTUUID, nil when no such partition."
  [partuuid]
  (let [{:keys [ok? out]} ($? lsblk -no "PKNAME" [(str "/dev/disk/by-partuuid/" partuuid)])]
    (when ok?
      (some->> out str/trim not-empty (str "/dev/")))))


(defn path->space
  "{:total-mb :free-mb} of the filesystem holding PATH, nil when it doesn't exist."
  [path]
  (let [f (fs/file (str path))]
    (when (.exists f)
      {:total-mb (quot (.getTotalSpace f) 1048576)
       :free-mb  (quot (.getUsableSpace f) 1048576)})))


(defn device->space
  "path->space of DEVICE's mountpoint, nil when unmounted or absent."
  [device]
  (when device
    (some-> (mount/device->mount-points device) first path->space)))
         