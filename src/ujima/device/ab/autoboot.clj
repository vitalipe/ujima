(ns ujima.device.ab.autoboot
  (:require
    [babashka.fs :as fs]
    [lib.io                 :refer [file->uint-be]]
    [ujima.linux.disk       :refer [device->partitions]]
    [ujima.linux.disk.mount :refer [with-mounted-vfat with-mounted-ext4]]
    [ujima.linux.sudo       :refer [sudo$!]]


    [ujima.pack :as pack]

    [ujima.device.ab                     :refer [UjimaSystemDisk UjimaBootRuntime]]
    [ujima.device.ab.autoboot.bootfiles  :as autoboot]
    [ujima.device.ab.autoboot.partitions :refer [ujima-mbr-disk-id
                                                 device->partitions-by-name
                                                 write-ab-partition-layout!
                                                 require-ab-partition-layout!  
                                                 ujima-ab-partition-layout?]]))


(defn- require-ab-slot! [?slot]
  (when-not (#{:a :b} ?slot)
    (throw
      (ex-info "Boot slot must be :a or :b" {:expected #{:a :b} :actual ?slot}))))


;; 1 control · 2 boot-a · 3 boot-b · 4 ext · 5 root-a · 6 root-b · 7 config · 8 storage
(def slot->idx {:a 2 :b 3})
(def idx->slot {2 :a  3 :b})


(def ujima-config-uuid  (format "%08x-%02x" ujima-mbr-disk-id 7))
(def ujima-storage-uuid (format "%08x-%02x" ujima-mbr-disk-id 8))


(def slot->boot-uuid {:a (format "%08x-%02x" ujima-mbr-disk-id 2)
                      :b (format "%08x-%02x" ujima-mbr-disk-id 3)})


(def slot->root-uuid {:a (format "%08x-%02x" ujima-mbr-disk-id 5)
                      :b (format "%08x-%02x" ujima-mbr-disk-id 6)})


(defn- slot->fstab
  "Per-slot /etc/fstab for an installed slot: the slot's own root + /boot/firmware, the shared
   settings partition and its per-slot bind onto /ujima/settings (the stable path the agent reads,
   so the agent never knows its slot), plus the shared storage partition; all by PARTUUID. Root is
   passno 1 so it's fsck'd first — the cmdline mounts it read-only, fsck runs, then systemd remounts
   it rw per this '/' entry.

   Settings is a REQUIRED mount (no `nofail`): the agent is meaningless without it, so a
   missing/corrupt settings partition must halt boot (emergency) rather than silently fall back to
   the empty rootfs mountpoint and run on defaults. Being required also means systemd's
   local-fs.target guarantees it is mounted before the agent starts — no mount check in agent code.
   fsck still runs first (passno 2, `fsck.repair=yes` in the cmdline) and auto-repairs the common
   power-loss case. `nofail` stays on boot-firmware/storage — those missing shouldn't brick an
   otherwise-correct boot."
  [slot]
  (str "PARTUUID=" (slot->root-uuid slot) "  /               ext4  defaults,noatime  0  1\n"
       "proc                  /proc           proc  defaults         0  0\n"
       "PARTUUID=" (slot->boot-uuid slot) "  /boot/firmware  vfat  defaults,nofail  0  2\n"

       "PARTUUID=" ujima-config-uuid      "  /mnt/settings   ext4  defaults         0  2\n"
       "PARTUUID=" ujima-storage-uuid     "  /mnt/storage    ext4  defaults,nofail  0  2\n"

       "/mnt/settings/" (name slot)       "  /ujima/settings none  bind             0  0\n"
       "/mnt/storage/"                    "  /ujima/storage  none  bind             0  0\n"))


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

    (let [{cfg-blk :config :as parts} (device->partitions-by-name device) 
          {:keys [root boot]}         (get parts slot)]

      (pack/unpack! ujima-pack-path boot root)

      (with-mounted-vfat [boot-mnt boot]
        (autoboot/cmdline! boot-mnt (str "PARTUUID=" (slot->root-uuid slot))))

      ;; per-slot fstab + mount points. Settings is bind-mounted per-slot onto /ujima/settings, so
      ;; the rootfs needs both the raw /mnt/settings mount point and the /ujima/settings bind target.
      (with-mounted-ext4 [root-mnt root]
        (fs/create-dirs (fs/path root-mnt "etc"))   ; real rootfs has it; a minimal/test root may not
        (spit (str (fs/path root-mnt "etc/fstab")) (slot->fstab slot))

        (fs/create-dirs (fs/path root-mnt "mnt/settings"))
        (fs/create-dirs (fs/path root-mnt "mnt/storage"))

        (fs/create-dirs (fs/path root-mnt "ujima/settings"))
        (fs/create-dirs (fs/path root-mnt "ujima/storage")))

      ;; create this slot's settings subdir 
      (with-mounted-ext4 [cfg-mnt cfg-blk]
        (fs/create-dirs (fs/path cfg-mnt (name slot))))))


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
    (not (zero? (file->uint-be "/proc/device-tree/chosen/bootloader/tryboot")))))



(defn ->disk [{:keys [device]}]
  (->AutobootDisk device))


(defn ->boot []
  (->AutobootRuntime))