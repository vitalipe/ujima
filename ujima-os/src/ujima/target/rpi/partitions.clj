(ns ujima.target.rpi.partitions
  (:require [ujima.linux.shell :refer [sh! sudo!]]
            [ujima.linux.disk :refer [require-block-device!
                                       device->partitions
                                       partition->info]]))


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
  (let [[control-start control-end] [4            (+ 4 64)]
        [boot-a-start boot-a-end]   [control-end  (+ control-end 512)]
        [boot-b-start boot-b-end]   [boot-a-end   (+ boot-a-end  512)]
        
        [ext-start    ext-end]      [boot-b-end   (+ boot-b-end  1)]
        
        [root-a-start root-a-end]   [ext-end      (+ ext-end     10240)]
        [root-b-start root-b-end]   [root-a-end   (+ root-a-end  10240)]
        [config-start config-end]   [root-b-end   (+ root-b-end  1024)]]

    (require-block-device! device) 

    (sudo! :wipefs "-a" device)
    (sudo! :parted "-s" device "mklabel" "msdos")

    (sudo! :parted "-s" device "mkpart" "primary" "fat32" (str control-start "MiB") (str control-end "MiB"))

    (sudo! :parted "-s" device "mkpart" "primary" "fat32" (str boot-a-start "MiB")     (str boot-a-end "MiB"))
    (sudo! :parted "-s" device "mkpart" "primary" "fat32" (str boot-b-start "MiB")     (str boot-b-end "MiB"))

    (sudo! :parted "-s" device "mkpart" "extended" (str  ext-start "MiB") "100%")

    (sudo! :parted "-s" device "mkpart" "logical" "ext4" (str root-a-start "MiB") (str root-a-end "MiB"))
    (sudo! :parted "-s" device "mkpart" "logical" "ext4" (str root-b-start "MiB") (str root-b-end "MiB"))
    (sudo! :parted "-s" device "mkpart" "logical" "ext4" (str config-start "MiB") (str config-end "MiB"))
    (sudo! :parted "-s" device "mkpart" "logical" "ext4" (str config-end   "MiB") "100%")

    (sudo! :partprobe device)
    (sh!   :udevadm "settle")
  
    ;; Format only persistent/control partitions here.
    ;; Boot/root partitions are written later from images.
    (let [{:keys [control config storage]} (device->partitions-by-name device)]
  
      (sudo! :mkfs.vfat "-F" "32" "-n" "UJCTL" control)

      (sudo! :mkfs.ext4 "-F" "-L" "UJCFG"   config)
      (sudo! :mkfs.ext4 "-F" "-L" "UJSTORE" storage))))
