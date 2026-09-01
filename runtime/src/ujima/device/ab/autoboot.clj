(ns ujima.device.ab.autoboot
  (:require
    [babashka.fs :as fs]
    [clojure.string :as str]
    [lib.io                 :refer [file->uint-be slurp-text]]
    [lib.task.flow          :refer [flow <step! <join!]]
    [ujima.log              :as log]
    [ujima.linux.disk       :refer [carries-data? device->signatures]]
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


;; 1 control · 2 boot-a · 3 boot-b · 4 ext · 5 root-a · 6 root-b · 7 config-a · 8 config-b · 9 logs · 10 storage
(def slot->idx {:a 2 :b 3})
(def idx->slot {2 :a  3 :b})


(def ujima-control-uuid (format "%08x-%02x" ujima-mbr-disk-id 1))
(def ujima-logs-uuid    (format "%08x-%02x" ujima-mbr-disk-id 9))
(def ujima-storage-uuid (format "%08x-%02x" ujima-mbr-disk-id 10))


(def slot->boot-uuid {:a (format "%08x-%02x" ujima-mbr-disk-id 2)
                      :b (format "%08x-%02x" ujima-mbr-disk-id 3)})


(def slot->root-uuid {:a (format "%08x-%02x" ujima-mbr-disk-id 5)
                      :b (format "%08x-%02x" ujima-mbr-disk-id 6)})


(def slot->config-uuid {:a (format "%08x-%02x" ujima-mbr-disk-id 7)
                        :b (format "%08x-%02x" ujima-mbr-disk-id 8)})


;; the disk's identity: a file at the control-partition root, next to
;; autoboot.txt — survives slot installs and board swaps
(def ^:private system-disk-id-file "system-disk-id")


(defn- read-system-disk-id [root]
  (some-> (slurp-text (fs/path root system-disk-id-file) nil) str/trim not-empty))


(defn- slot->fstab
  "Per-slot /etc/fstab for an installed slot: /boot/firmware, the slot's OWN settings
   partition mounted directly at /ujima/settings (the stable path ujimad reads, so it
   never knows its slot — mounting the slot's partition IS slot selection), plus the
   shared logs and storage partitions; all by PARTUUID.
   Root is NOT listed — the kernel mounts it from the cmdline and overlayroot overlays it
   (read-only lower + tmpfs upper) and rewrites this fstab itself, so a '/' entry would be
   pointless (overlayroot just comments it out) and systemd-remount-fs, which would act on it,
   is masked in the ujimaify stage (overlayfs rejects its remount).

   Settings is a REQUIRED mount (no `nofail`): ujimad is meaningless without it, so a
   missing/corrupt settings partition must halt boot (emergency) rather than silently fall back to
   the empty rootfs mountpoint and run on defaults — and per-slot partitions mean it halts only
   THIS slot; the other slot boots on its own settings. Being required also means systemd's
   local-fs.target guarantees it is mounted before ujimad starts — no mount check in ujimad code.
   `data=journal`: full data journaling — the partition is tiny and rarely written, so the cost
   is nothing and a mid-write power cut can't tear a settings file.
   `nofail` stays on boot-firmware/logs/storage — those missing shouldn't brick an
   otherwise-correct boot. Logs is shared so a failed slot's journal stays readable
   from the other slot; journald writes it through the /var/log/journal bind."
  [slot]
  (str "proc                  /proc             proc  defaults              0  0\n"
       "PARTUUID=" (slot->boot-uuid slot)   "  /boot/firmware    vfat  defaults,nofail       0  2\n"

       "PARTUUID=" (slot->config-uuid slot) "  /ujima/settings   ext4  noatime,data=journal  0  2\n"
       "PARTUUID=" ujima-logs-uuid          "  /ujima/logs       ext4  noatime,nofail        0  2\n"
       "PARTUUID=" ujima-storage-uuid       "  /ujima/storage    ext4  noatime,nofail        0  2\n"

       "/ujima/logs/journal   /var/log/journal  none  bind,nofail           0  0\n"))


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
      (let [{:keys [a b logs storage control]} (device->partitions-by-name device)]
        (with-mounted-vfat [ctl-mnt control]
          (let [meta-a (pack/install-record (:root a))
                meta-b (pack/install-record (:root b))]

            (when-let [{boot-idx :boot try-boot-idx :try-boot} (autoboot/autoboot ctl-mnt)]
              {:device  device
               :type    :ab
               :storage storage
               :logs    logs
               :system-disk-id (read-system-disk-id ctl-mnt)
               :slots   {:a (assoc a :ujima-os meta-a)
                         :b (assoc b :ujima-os meta-b)}

               :boot-slot     (idx->slot boot-idx)
               :try-boot-slot (idx->slot try-boot-idx)}))))))


  ;; the disk is the oracle: kernel nodes outlive a `wipefs -a`, and unpartitioned
  ;; media has none at all.
  (write-ujima-layout! [_]
    (when (carries-data? device)
      (throw
        (ex-info "Refusing to write Ujima layout: device carries data"
                 {:device     device
                  :signatures (device->signatures device)})))

    (flow :layout
      (<step! 100 :partition
        (progress! 0 "A/B partition table + filesystems")
        (write-ab-partition-layout! device))
      {:device device}))


  (install-into-slot! [_ ujima-pack-path slot]

    (require-ab-slot! slot)
    (require-ab-partition-layout! device)

    (let [{logs-blk :logs :as parts}          (device->partitions-by-name device)
          {:keys [root boot] cfg-blk :config} (get parts slot)]

      (flow :install-slot
        (<join! 90 (pack/unpack! ujima-pack-path boot root {:slot slot}))

        (<step! 100 :wire
          (progress! 0 "root=, fstab, settings + logs dirs")

          ;; re-point `root` at this slot; the rest of the line is the pack's own
          (with-mounted-vfat [boot-mnt boot]
            (autoboot/cmdline! boot-mnt
                               (autoboot/cmdline-assoc (autoboot/cmdline boot-mnt)
                                                       "root" (str "PARTUUID=" (slot->root-uuid slot)))))

          ;; per-slot fstab. Its mount points / bind targets are rootfs content baked at build
          ;; time (the base stage) — a pack is expected to carry them.
          (let [ujimad-owner
                (with-mounted-ext4 [root-mnt root]
                  (fs/create-dirs (fs/path root-mnt "etc"))   ; real rootfs has it; a minimal/test root may not
                  (spit (str (fs/path root-mnt "etc/fstab")) (slot->fstab slot))
                  (rootfs-owner root-mnt "ujima"))]

            ;; this slot's own settings partition, its root owned by the ujimad user so it can
            ;; write the device scope through the /ujima/settings mount (a root-owned dir breaks
            ;; every device-scope write); lost+found beside the scope files is inert
            (with-mounted-ext4 [cfg-mnt cfg-blk]
              (when ujimad-owner
                (sudo$! chown [ujimad-owner] [cfg-mnt]))))

          ;; journald journal dir on the shared logs partition (the /var/log/journal bind source)
          (with-mounted-ext4 [logs-mnt logs-blk]
            (fs/create-dirs (fs/path logs-mnt "journal"))))

        {:slot slot})))


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

    nil)


  (system-disk-id! [_]
    (require-ab-partition-layout! device)
    (let [{control :control} (device->partitions-by-name device)]
      (with-mounted-vfat [ctl-mnt control]
        (or (read-system-disk-id ctl-mnt)
            (let [id (str (java.util.UUID/randomUUID))]
              ;; root-owned partition root; install(1) like pack's manifest write.
              ;; A plain write is right for FAT (no atomic rename): the file is
              ;; write-once on an otherwise-quiet partition.
              (fs/with-temp-dir [tmp {:prefix "ujima-system-disk-id-"}]
                (spit (str (fs/path tmp system-disk-id-file)) (str id "\n"))
                (sudo$! install -m "0644"
                        (fs/path tmp system-disk-id-file)
                        (fs/path ctl-mnt system-disk-id-file)))
              (log/info "system-disk-id stamped" {:id id})
              id))))))


(defrecord AutobootRuntime []

  UjimaBootRuntime

  (try-boot! [_]
    ;; ONE argument — the firmware parses "0 tryboot"; two args is reboot(8)'s
    ;; "Too many arguments." (found on first real invocation)
    (sudo$! reboot "0 tryboot"))

  (in-try-boot? [_]
    (not (zero? (file->uint-be "/proc/device-tree/chosen/bootloader/tryboot")))))


(defn ->disk [{:keys [device]}]
  (->AutobootDisk device))


(defn ->boot-runtime []
  (->AutobootRuntime))