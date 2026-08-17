(ns ujima.linux.disk.block-test
  "The partition filter, against lsblk's real shape (captured from a dev Pi, util-linux 2.41.5)."
  (:require [clojure.test  :refer [deftest is]]
            [cheshire.core :as json]
            [ujima.linux.disk.block :as block]))


(def ^:private partitions-of #'block/partitions-of)


(defn- rows [json-text] (:blockdevices (json/parse-string json-text true)))


;; the dev Pi with the stick in: an internal card, zram, and one SanDisk with no label
(def ^:private pi-with-stick
  (json/generate-string
    {:blockdevices
     [{:path "/dev/sda"       :pkname nil        :type "disk" :uuid nil :label nil
       :fstype nil    :size "28.7G" :rm true}
      {:path "/dev/sda1"      :pkname "sda"      :type "part" :uuid "6962-5E15" :label nil
       :fstype "vfat" :size "28.7G" :rm true}
      {:path "/dev/mmcblk0"   :pkname nil        :type "disk" :uuid nil :label nil
       :fstype nil    :size "29.5G" :rm false}
      {:path "/dev/mmcblk0p5" :pkname "mmcblk0"  :type "part" :uuid "687fa5d6" :label "rootfs"
       :fstype "ext4" :size "10G"   :rm false}
      {:path "/dev/mmcblk0p6" :pkname "mmcblk0"  :type "part" :uuid nil :label nil
       :fstype nil    :size "10G"   :rm false}
      {:path "/dev/mmcblk0p7" :pkname "mmcblk0"  :type "part" :uuid "1fcfe82d" :label "UJCFG"
       :fstype "ext4" :size "1G"    :rm false}
      {:path "/dev/mmcblk0p8" :pkname "mmcblk0"  :type "part" :uuid "be558b4a" :label "UJSTORE"
       :fstype "ext4" :size "5.9G"  :rm false}
      {:path "/dev/zram0"     :pkname nil        :type "disk" :uuid nil :label nil
       :fstype nil    :size "0B"    :rm false}]}))


(deftest one-stick-is-one-entry-and-it-is-the-partition
  (is (= {"6962-5E15" {:uuid "6962-5E15" :disk "sda" :label nil :fstype "vfat" :size "28.7G"}}
         (partitions-of (rows pi-with-stick)))
      "/dev/sda is removable too, but a disk is not a mountable thing — and the card is not media"))


(deftest each-filter-does-work
  (let [part {:path "/dev/sda1" :pkname "sda" :type "part" :uuid "U" :label "L"
              :fstype "vfat" :size "1G" :rm true}]
    (is (seq  (partitions-of [part])))
    (is (= {} (partitions-of [(assoc part :rm false)]))
        "not removable — the boot disk's partitions are not user media")
    (is (= {} (partitions-of [(assoc part :type "disk")]))
        "a filesystem on a whole device (no partition table) is out of scope")
    (is (= {} (partitions-of [(assoc part :uuid nil)]))
        "no uuid = no stable identity to key on")))


(deftest rm-as-a-string-still-means-removable
  ;; older lsblk emits "0"/"1", and "0" is truthy in clojure — the trap this guards
  (let [part {:type "part" :uuid "U" :pkname "sda" :label "L" :fstype "vfat" :size "1G"}]
    (is (seq  (partitions-of [(assoc part :rm "1")])))
    (is (= {} (partitions-of [(assoc part :rm "0")])))))
