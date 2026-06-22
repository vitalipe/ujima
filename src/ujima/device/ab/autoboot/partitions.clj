(ns ujima.device.ab.autoboot.partitions
  (:require [lib.shell :refer [$!]]
            [ujima.linux.sudo :refer [sudo$!]]
            [ujima.linux.disk :refer [require-block-device!
                                       device->partitions
                                       partition->info]]))


(def ujima-mbr-disk-id  0x00C0FFEE)
(def ujima-root-a-uuid  (format "%08x-%02x" ujima-mbr-disk-id 5))
(def ujima-root-b-uuid  (format "%08x-%02x" ujima-mbr-disk-id 6))


(defn- MiB [v] 
  (* 1024 1024 v))


(defn require-ab-partition-layout! [device]
  (require-block-device! device)

  (let [[?control ?boot-a ?boot-b _ext
         ?root-a ?root-b ?config ?storage :as partitions]
        (mapv partition->info (device->partitions device))]

    (when-not (= 8 (count partitions))
      (throw
        (ex-info "Device does not have the expected Ujima partition number"
          {:device   device
           :expected 8
           :actual   (count partitions)})))

    (when-not (>= (:size-bytes ?control) (MiB 64))
      (throw
        (ex-info "Control partition is too small"
          {:device device :expected (MiB 64) :actual (:size-bytes ?control)})))

    (when-not (>= (:size-bytes ?boot-a) (MiB 512))
      (throw
        (ex-info "Boot(a) partition is too small"
          {:device device :expected (MiB 512) :actual (:size-bytes ?boot-a)})))

    (when-not (>= (:size-bytes ?boot-b) (MiB 512))
      (throw
        (ex-info "Boot(b) partition is too small"
          {:device device :expected (MiB 512) :actual (:size-bytes ?boot-b)})))

    (when-not (>= (:size-bytes ?root-a) (MiB 10240))
      (throw
        (ex-info "Root(a) partition is too small"
          {:device device :expected (MiB 10240) :actual (:size-bytes ?root-a)})))

    (when-not (>= (:size-bytes ?root-b) (MiB 10240))
      (throw
        (ex-info "Root(b) partition is too small"
          {:device device :expected (MiB 10240) :actual (:size-bytes ?root-b)})))

    (when-not (>= (:size-bytes ?config) (MiB 1024))
      (throw
        (ex-info "Config partition is too small"
          {:device device :expected (MiB 1024) :actual (:size-bytes ?config)})))

    (when-not ?storage
      (throw
        (ex-info "Storage partition is missing"
          {:device device})))

    true))


(defn ujima-ab-partition-layout? [device]
  (try
    (require-ab-partition-layout! device)
    true    
    (catch Throwable _ false)))


(defn device->partitions-by-name [device]
  (when (ujima-ab-partition-layout? device)
    (let [[control ,boot-a, boot-b,_ext,root-a,root-b, config storage] (device->partitions device)]
      {:control control
       :config  config
       :storage storage
       :a       {:boot boot-a :root root-a} 
       :b       {:boot boot-b :root root-b}})))

  
(defn write-ab-partition-layout! [device]
  (let [MiB #(str % "MiB")
        [control-start control-end] [4            (+ 4 64)]
        [boot-a-start boot-a-end]   [control-end  (+ control-end 512)]
        [boot-b-start boot-b-end]   [boot-a-end   (+ boot-a-end  512)]
        
        ext-start                    boot-b-end
        
        ;; +1 for EBR
        [root-a-start root-a-end]   [(+ ext-start  1)  (+ ext-start  10240 1)]
        [root-b-start root-b-end]   [(+ root-a-end 1)  (+ root-a-end 10240 1)]
        [config-start config-end]   [(+ root-b-end 1)  (+ root-b-end 1024  1)]
        [storage-start]             [(+ config-end 1)]]

    (require-block-device! device) 

    (sudo$! wipefs -a [device])
    (sudo$! parted -s        [device] "mklabel" "msdos")
    (sudo$! sfdisk --disk-id [device ujima-mbr-disk-id]) ;; <-- we need this for cmdline.txt

    (sudo$! parted -s [device] mkpart primary fat32 (MiB control-start) (MiB control-end))

    (sudo$! parted -s [device] mkpart primary fat32 (MiB boot-a-start)  (MiB boot-a-end))
    (sudo$! parted -s [device] mkpart primary fat32 (MiB boot-b-start)  (MiB boot-b-end))

    (sudo$! parted -s [device] mkpart extended (MiB  ext-start) "100%")

    (sudo$! parted -s [device] mkpart logical ext4 (MiB root-a-start) (MiB root-a-end))
    (sudo$! parted -s [device] mkpart logical ext4 (MiB root-b-start) (MiB root-b-end))
    (sudo$! parted -s [device] mkpart logical ext4 (MiB config-start) (MiB config-end))
    (sudo$! parted -s [device] mkpart logical ext4 (MiB storage-start) "100%")

    (sudo$! partprobe [device])
    ($!     udevadm settle)
  
    ;; Format only persistent/control partitions here.
    ;; Boot/root partitions are written later from images.
    (let [{:keys [control config storage]} (device->partitions-by-name device)]
  
      (sudo$! :mkfs.vfat -F 32 -n "UJCTL" [control])

      (sudo$! :mkfs.ext4 -F -L "UJCFG"   [config])
      (sudo$! :mkfs.ext4 -F -L "UJSTORE" [storage]))))
