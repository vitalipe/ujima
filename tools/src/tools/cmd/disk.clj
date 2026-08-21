(ns tools.cmd.disk
  "The full-disk column, one subtree per boot scheme (autoboot today): every verb
   takes the disk TARGET last — an .img file (loopback-attached) or a real block
   device, the same object either way. `autoboot from-pack` is the installer:
   layout -> slot A -> activate, rendered as one task."
  (:require
    [clojure.string :as str]
    [clojure.pprint :refer [pprint]]
    [babashka.fs :as fs]
    [babashka.process :as p]
    [lib.io :refer [slurp-edn]]
    [lib.shell :refer [$! require-root!]]
    [lib.task.flow :refer [flow <step!]]
    [ujima.linux.disk :refer [block-device? device->partitions]]
    [ujima.linux.disk.loop :as loopback]
    [ujima.linux.disk.mount :as mount]
    [ujima.pack :as ujima-pack]
    [ujima.device.ab :as ab]
    [ujima.device.ab.autoboot :as ab-auto]
    [ujima.device.ab.autoboot.partitions :as partitions]
    [tools.cmd.pack :as pack]))


;; 28 GiB fits a real 32 GB card; the storage partition fills the actual medium.
(def ^:private image-bytes 30064771072)


(defn- with-disk*
  "Run f on the disk's block device: a real device passes through, an .img file is
   loopback-attached for the extent of f."
  [target f]
  (if (block-device? target)
    (f target)
    (loopback/with-loopback-device [dev target]
      (f dev))))


(defn- ->slot [s]
  (or (get {"a" :a "b" :b} (str/lower-case (str s)))
      (throw (ex-info "slot must be A or B" {:actual s}))))


;; this file IS the autoboot subtree, so verbs construct its record directly;
;; A/B operations still gate on the disk's actual layout.
(defn- ->disk [dev]
  (ab-auto/->disk {:device dev}))


(defn- prepare-media!
  "An .img target is (re)created sparse; a block device with partitions refuses
   without `wipe`."
  [target wipe]
  (if (block-device? target)
    (when (seq (device->partitions target))
      (when-not wipe
        (throw (ex-info "device already has partitions — pass --wipe to destroy them"
                        {:target target})))
      ($! wipefs -a [target])
      ($! partprobe [target])
      ($! udevadm settle))
    (do (fs/delete-if-exists target)
        ($! truncate -s (str image-bytes) (str target)))))


;; ── seeding ─────────────────────────────────────────────────────────────────

;; the same vendored qemu the image build injects — binfmt resolves it at this
;; fixed path INSIDE the chroot
(def ^:private qemu-src    "os/build/vendor/qemu-aarch64-static")
(def ^:private qemu-chroot "/usr/bin/qemu-aarch64-static")


(defn- chroot-classpath
  "The slot runtime's own classpath, chroot-relative: src + every baked m2 jar."
  [root-mnt]
  (let [jars (fs/glob (str root-mnt "/ujima/m2") "**.jar")]
    (when (empty? jars)
      (throw (ex-info "slot runtime has no /ujima/m2 jars — image built from a stale vendor?"
                      {:root (str root-mnt)})))
    (->> jars
         (map #(str/replace (str %) (str root-mnt) ""))
         (sort)
         (cons "src")
         (str/join ":"))))


(defn- run-importer! [root-mnt seed-file]
  (let [seed-chroot "/tmp/ujima-seed.edn"
        binds       ["/dev" "/proc" "/sys"]]
    (try
      (doseq [b binds] ($! mount --bind [b] (str root-mnt b)))
      ($! cp [qemu-src] (str root-mnt qemu-chroot))
      ($! cp [seed-file] (str root-mnt seed-chroot))
      (p/shell {:inherit true}
               "chroot" (str root-mnt) "/bin/sh" "-c"
               (str "cd /ujima/ujimad && exec /usr/local/bin/bb"
                    " -cp " (chroot-classpath root-mnt)
                    " -m ujima.importer " seed-chroot))
      (finally
        ($! rm -f (str root-mnt seed-chroot) (str root-mnt qemu-chroot))
        (doseq [b (reverse binds)]
          (when (mount/mount-point? (str root-mnt b))
            ($! umount (str root-mnt b))))))))


(defn- seed-slot!
  "Run the slot's OWN runtime importer over the seed file: mount the slot root,
   bind this slot's UJCFG dir where the runtime expects /ujima/settings, chroot
   (aarch64 bb under qemu), import. Any invalid entry throws — nothing applied."
  [dev slot seed-file]
  (let [{cfg-blk :config :as parts} (partitions/device->partitions-by-name dev)
        {:keys [root]}              (get parts slot)]
    (mount/with-mounted-ext4 [root-mnt root]
      (mount/with-mounted-ext4 [cfg-mnt cfg-blk]
        (let [slot-cfg (str (fs/path cfg-mnt (name slot)))
              settings (str root-mnt "/ujima/settings")]
          (try
            ($! mount --bind [slot-cfg] [settings])
            (run-importer! root-mnt seed-file)
            (finally
              (when (mount/mount-point? settings)
                ($! umount [settings])))))))))


(defn- require-seed-file!
  "Fail-fast syntactic gate; the pack's own runtime is the semantic authority."
  [seed-file]
  (let [entries (slurp-edn seed-file ::unreadable)]
    (when (or (= ::unreadable entries)
              (not (sequential? entries))
              (empty? entries))
      (throw (ex-info "settings file unreadable or not a non-empty vector of entries"
                      {:settings seed-file})))))


(defn empty!
  "Write an empty autoboot A/B layout onto blank media."
  [{:keys [target wipe]}]
  (require-root!)
  (prepare-media! target wipe)
  (with-disk* target
    (fn [dev] (ab/write-ujima-layout! (->disk dev))))
  (println "created autoboot A/B layout ->" target))


(defn from-pack!
  "The installer: layout -> slot A -> seed (when given) -> activate, as a cold
   flow the CLI wrapper runs + renders."
  [{:keys [pack target settings wipe]}]
  (require-root!)
  (flow :install
    (<step! 5 :preflight
      (progress! 0 "validating pack")
      (ujima-pack/validate! pack)
      (when settings
        (require-seed-file! settings)))

    (<step! 10 :media
      (progress! 0 "preparing target media")
      (prepare-media! target wipe))

    (with-disk* target
      (fn [dev]
        (let [disk (->disk dev)]
          (<step! 20 :layout
            (progress! 0 "writing A/B layout")
            (ab/write-ujima-layout! disk))

          (<step! 90 :slot
            (progress! 0 "installing slot A (boot + root, ~10.5G)")
            (ab/install-into-slot! disk pack :a))

          (when settings
            (<step! 97 :seed
              (progress! 0 "seeding settings (slot A chroot)")
              (seed-slot! dev :a settings)))

          (<step! 100 :activate
            (progress! 0 "activating slot A")
            (ab/set-boot-slot! disk :a)))))

    {:pack (str pack) :target (str target) :slot :a :seeded (boolean settings)}))


(defn slot!
  "Dispatch `disk autoboot slot <A|B> <verb> …`:
   from-pack <pack> <target> [settings.edn] | from-image <img> <target> [settings.edn] |
   activate <target>."
  [{:keys [slot verb a b c]}]
  (require-root!)
  (let [slot     (->slot slot)
        install! (fn [pack target]
                   (when c (require-seed-file! c))
                   (with-disk* target
                     (fn [dev]
                       (ab/install-into-slot! (->disk dev) pack slot)
                       (when c
                         (println "seeding settings -> slot" (name slot))
                         (seed-slot! dev slot c)))))]
    (case verb
      "from-pack"
      (do (when-not (and a b)
            (throw (ex-info "usage: disk autoboot slot <A|B> from-pack <pack> <img|blockdev> [settings.edn]" {})))
          (install! a b)
          (println "installed" a "-> slot" (name slot) "on" b))

      ;; SLOW by choice: packs to a throwaway file, because unpack! only reads tar members.
      "from-image"
      (do (when-not (and a b)
            (throw (ex-info "usage: disk autoboot slot <A|B> from-image <img> <img|blockdev> [settings.edn]" {})))
          (fs/with-temp-dir [work {:prefix "ujima-from-image-"}]
            (let [tmp-pack (str (fs/path work "image.pack"))]
              (println "packing" a "-> temp pack")
              (pack/make! {:src a :out tmp-pack})
              (install! tmp-pack b)))
          (println "installed" a "-> slot" (name slot) "on" b))

      "activate"
      (do (when (or (nil? a) b c)
            (throw (ex-info "usage: disk autoboot slot <A|B> activate <img|blockdev>" {})))
          (with-disk* a (fn [dev] (ab/set-boot-slot! (->disk dev) slot)))
          (println "boot slot ->" (name slot) "on" a))

      (throw (ex-info (str "unknown slot verb: " verb)
                      {:expected #{"from-pack" "from-image" "activate"}})))))


(defn info!
  "Print the disk's ujima view: slots + installed metadata + boot selection, nil-ish
   message when the target carries no ujima A/B layout."
  [{:keys [target]}]
  (require-root!)
  (let [info (with-disk* target (fn [dev] (ab/ujima-disk-info (->disk dev))))]
    (if info
      (pprint info)
      (println "not a ujima A/B disk:" target))))


;; ── the CLI ─────────────────────────────────────────────────────────────────

(def cli
  {"disk"
   {"autoboot"
    {"empty"
     {:usage "Usage: disk autoboot empty <img|blockdev> [--wipe]"
      :target empty!
      :args [:target]
      :spec {:target {:desc "Disk target: .img file or block device" :require true}
             :wipe   {:coerce :boolean
                      :desc "Destroy existing partitions on a block device"}}}

     "from-pack"
     {:usage "Usage: disk autoboot from-pack <pack> <img|blockdev> [settings.edn] [--wipe]"
      :target from-pack!
      :args [:pack :target :settings]
      :spec {:pack     {:desc "The .pack artifact" :require true}
             :target   {:desc "Disk target: .img file or block device" :require true}
             :settings {:desc "Settings command file, applied by the slot's own runtime"}
             :wipe     {:coerce :boolean
                        :desc "Destroy existing partitions on a block device"}}}

     "slot"
     {:usage "Usage: disk autoboot slot <A|B> from-pack <pack> <img|blockdev> [settings.edn]\n       disk autoboot slot <A|B> from-image <img> <img|blockdev> [settings.edn]\n       disk autoboot slot <A|B> activate <img|blockdev>"
      :target slot!
      :args [:slot :verb :a :b :c]
      :spec {:slot {:desc "Slot: A or B" :require true}
             :verb {:desc "from-pack | from-image | activate" :require true}
             :a    {:desc "from-pack/from-image: the source | activate: the disk target" :require true}
             :b    {:desc "from-pack/from-image: the disk target"}
             :c    {:desc "from-pack/from-image: optional settings command file"}}}}

    "info"
    {:usage "Usage: disk info <img|blockdev>"
     :target info!
     :args [:target]
     :spec {:target {:desc "Disk target: .img file or block device" :require true}}}}})
