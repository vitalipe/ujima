(ns ujima.device.ab.autoboot
  (:require
    [babashka.fs :as fs]
    [clojure.string :as str]
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
  "Per-slot /etc/fstab for an installed slot: /boot/firmware, the shared settings partition and its
   per-slot bind onto /ujima/settings (the stable path ujimad reads, so it never knows its slot),
   plus the shared storage partition mounted directly at /ujima/storage; all by PARTUUID.
   /mnt/settings (the one path outside /ujima) holds both slot dirs — the bind IS slot selection.
   Root is NOT listed — the kernel mounts it from the cmdline and overlayroot overlays it
   (read-only lower + tmpfs upper) and rewrites this fstab itself, so a '/' entry would be
   pointless (overlayroot just comments it out) and systemd-remount-fs, which would act on it,
   is masked in os.ujimaify (overlayfs rejects its remount).

   Settings is a REQUIRED mount (no `nofail`): ujimad is meaningless without it, so a
   missing/corrupt settings partition must halt boot (emergency) rather than silently fall back to
   the empty rootfs mountpoint and run on defaults. Being required also means systemd's
   local-fs.target guarantees it is mounted before ujimad starts — no mount check in ujimad code.
   `nofail` stays on boot-firmware/storage — those missing shouldn't brick an otherwise-correct boot."
  [slot]
  (str "proc                  /proc           proc  defaults         0  0\n"
       "PARTUUID=" (slot->boot-uuid slot) "  /boot/firmware  vfat  defaults,nofail  0  2\n"

       "PARTUUID=" ujima-config-uuid      "  /mnt/settings   ext4  defaults         0  2\n"
       "PARTUUID=" ujima-storage-uuid     "  /ujima/storage  ext4  defaults,nofail  0  2\n"

       "/mnt/settings/" (name slot)       "  /ujima/settings none  bind             0  0\n"
       "/ujima/storage/logs"              "  /var/log/journal none  bind,nofail     0  0\n"))


(defn- rootfs-owner
  ;; "uid:gid" of `user` in an unpacked rootfs, nil when absent (minimal test roots) —
  ;; chown must use the TARGET's numeric ids; the installing host doesn't have the user.
  [root-mnt user]
  (let [passwd (fs/path root-mnt "etc/passwd")
        line   (when (fs/exists? passwd)
                 (->> (str/split-lines (slurp (str passwd)))
                      (filter #(str/starts-with? % (str user ":")))
                      (first)))]
    (when line
      (let [[_name _pw uid gid] (str/split line #":")]
        (str uid ":" gid)))))


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

    (let [{cfg-blk :config storage-blk :storage :as parts} (device->partitions-by-name device)
          {:keys [root boot]}         (get parts slot)]

      (pack/unpack! ujima-pack-path boot root)

      (with-mounted-vfat [boot-mnt boot]
        (autoboot/cmdline! boot-mnt (str "PARTUUID=" (slot->root-uuid slot))))

      ;; per-slot fstab. Its mount points / bind targets are rootfs content baked at build
      ;; time (os.base) — a pack is expected to carry them.
      (let [ujimad-owner
            (with-mounted-ext4 [root-mnt root]
              (fs/create-dirs (fs/path root-mnt "etc"))   ; real rootfs has it; a minimal/test root may not
              (spit (str (fs/path root-mnt "etc/fstab")) (slot->fstab slot))
              (rootfs-owner root-mnt "ujima"))]

        ;; this slot's settings subdir, owned by the ujimad user so it can write the device scope
        ;; through the /ujima/settings bind (a root-owned dir breaks every device-scope write)
        (with-mounted-ext4 [cfg-mnt cfg-blk]
          (let [slot-dir (fs/path cfg-mnt (name slot))]
            (fs/create-dirs slot-dir)
            (when ujimad-owner
              (sudo$! chown [ujimad-owner] [slot-dir])))))

      ;; journald logs dir on storage (the /var/log/journal bind source)
      (with-mounted-ext4 [storage-mnt storage-blk]
        (fs/create-dirs (fs/path storage-mnt "logs")))))


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