(ns ujima.device.ab.autoboot
  (:require
    [babashka.fs :as fs]        
    [ujima.linux.disk       :refer [device->partitions]]
    [ujima.linux.disk.mount :refer [with-mounted-vfat]]
    [ujima.linux.shell      :refer [sudo$!]]


    [ujima.pack :as pack]

    [ujima.device.ab                     :refer [UjimaSystemDisk UjimaBootRuntime]]
    [ujima.device.ab.autoboot.bootfiles  :as autoboot]
    [ujima.device.ab.autoboot.partitions :refer [ujima-root-a-uuid
                                                 ujima-root-b-uuid 
                                                 device->partitions-by-name
                                                 write-ab-partition-layout!
                                                 require-ab-partition-layout!  
                                                 ujima-ab-partition-layout?]]))


(defn- require-ab-slot! [?slot]
  (when-not (#{:a :b} ?slot)
    (throw
      (ex-info "Boot slot must be :a or :b" {:expected #{:a :b} :actual ?slot}))))


(defn file->u32-or-0 [path]
  (if-not (fs/exists? path)
    0
    (->> (fs/read-all-bytes path)
         (reduce (fn [a b] (+ (* a 256) (bit-and b 0xff))) 0))))


(def slot->idx {:a 2 :b 3})
(def idx->slot {2 :a  3 :b})


(defrecord AutobootDisk [device]

  UjimaSystemDisk

  (ujima-disk-info [{device :device}]
    (when (ujima-ab-partition-layout? device)
      (let [{:keys [a b config storage control]} (device->partitions-by-name device)]
        (with-mounted-vfat [ctl-mnt control]
          (let [meta-a (pack/installed-metadata (:root a))
                meta-b (pack/installed-metadata (:root b))]

            (when-let [{boot-idx :boot try-boot-idx :try-boot} (autoboot/autoboot ctl-mnt)]
              {:device  device
               :storage storage
               :config  config
               :slots   {:a (assoc a :ujima-os meta-a) 
                         :b (assoc b :ujima-os meta-b)}
             
               :boot-slot     (idx->slot boot-idx) 
               :try-boot-slot (idx->slot try-boot-idx)}))))))


  (write-ujima-layout! [_]
    (when-not (empty? (device->partitions device))
      (throw
        (ex-info "Refusing to write Ujima layout: device already has partitions"
                 {:device     device})))

    (write-ab-partition-layout! device))


  (install-into-slot! [_ ujima-pack-path slot]

    (require-ab-slot! slot)   
    (require-ab-partition-layout! device)
    (pack/validate! ujima-pack-path)

    (let [{:keys [boot root]} (-> device 
                                (device->partitions-by-name)
                                (get slot))]
          
      (pack/unpack! ujima-pack-path boot root)

      (with-mounted-vfat [boot-mnt boot]
        (autoboot/cmdline! boot-mnt (str "PARTUUID=" (case slot :a ujima-root-a-uuid 
                                                                :b ujima-root-b-uuid))))))


  (set-boot-slot! [_ slot]
    (require-ab-partition-layout! device)
    (require-ab-slot! slot) 

    (let [{ctl :control} (device->partitions-by-name device)]
      (with-mounted-vfat [ctl-mnt ctl]
        (autoboot/autoboot! ctl-mnt {:boot (slot->idx slot) :try-boot nil})))
    
    nil)
 

  (set-try-boot-slot! [_ slot]
    (require-ab-partition-layout! device)
    
    (when-not (nil? slot) ;; nil is valid to reset try-boot
      (require-ab-slot! slot)) 

    (let [{ctl :control} (device->partitions-by-name device)
          try-boot-idx   (slot->idx slot)]

      (with-mounted-vfat [ctl-mnt ctl]
        (let [{boot-idx :boot} (autoboot/autoboot ctl-mnt)]
          (autoboot/autoboot! ctl-mnt {:boot (or boot-idx try-boot-idx) 
                                       :try-boot try-boot-idx}))))

    nil)) 


(defrecord AutobootRuntime [] 
  
  UjimaBootRuntime
  
  (try-boot! [_]
    (sudo$! reboot 0 tryboot))

  (in-try-boot? [_]
    (not (zero? (file->u32-or-0 "/proc/device-tree/chosen/bootloader/tryboot")))))



(defn ->disk [{:keys [device]}]
  (->AutobootDisk device))


(defn ->boot []
  (->AutobootRuntime))