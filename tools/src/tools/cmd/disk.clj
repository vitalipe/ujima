(ns tools.cmd.disk
  "The full-disk column: create an A/B layout, install packs into slots, activate a slot,
   inspect. Every verb takes the disk TARGET last — an .img file (loopback-attached) or a
   real block device, the same object either way. The installer flow is these verbs in
   order: ab create -> slot A from-pack -> slot A activate."
  (:require
    [clojure.string :as str]
    [clojure.pprint :refer [pprint]]
    [babashka.fs :as fs]
    [lib.shell :refer [$! require-root!]]
    [ujima.linux.disk :refer [block-device?]]
    [ujima.linux.disk.loop :as loopback]
    [ujima.device.ab :as ab]
    [ujima.device.ab.autoboot :as ab-auto]
    [tools.cmd.pack :as pack]))


;; boot-scheme registry — `bb disk ab create <scheme>`; uefi lands beside autoboot as one
;; entry + a UjimaSystemDisk record. image-bytes is a constant per scheme (28 GiB fits a
;; real 32 GB card; the storage partition fills the rest).
(def ^:private schemes
  {"autoboot" {:->disk ab-auto/->disk :image-bytes 30064771072}})


(defn- resolve-scheme [scheme]
  (or (get schemes scheme)
      (throw (ex-info "Unknown boot scheme"
                      {:expected (set (keys schemes)) :actual scheme}))))


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


;; only one scheme exists, so verbs construct its record directly; every A/B operation
;; gates on the actual partition layout and fails loudly on a non-ujima disk. Scheme
;; detection replaces this when a second scheme lands.
(defn- ->disk [dev]
  (ab-auto/->disk {:device dev}))


(defn ab-create!
  "Write an empty A/B layout onto the target. A missing/existing .img target is
   (re)created sparse at the scheme's size; a block device must be partitionless
   (write-ujima-layout! refuses otherwise)."
  [{:keys [scheme target]}]
  (require-root!)
  (let [{:keys [image-bytes]} (resolve-scheme scheme)]
    (when-not (block-device? target)
      (fs/delete-if-exists target)
      ($! truncate -s (str image-bytes) (str target)))
    (with-disk* target
      (fn [dev] (ab/write-ujima-layout! (->disk dev))))
    (println "created" scheme "A/B layout ->" target)))


(defn slot!
  "Dispatch `disk slot <A|B> <verb> …`:
   from-pack <pack> <target> | from-image <img> <target> | activate <target>."
  [{:keys [slot verb a b]}]
  (require-root!)
  (let [slot (->slot slot)]
    (case verb
      "from-pack"
      (do (when-not (and a b)
            (throw (ex-info "usage: disk slot <A|B> from-pack <pack> <img|blockdev>" {})))
          (with-disk* b (fn [dev] (ab/install-into-slot! (->disk dev) a slot)))
          (println "installed" a "-> slot" (name slot) "on" b))

      ;; SLOW: packs to a throwaway file and installs that, because unpack! only reads tar
      ;; members. Costs a full pack (time + ~1.5x the image in temp space) and stamps the slot
      ;; with pack/make!'s default metadata. Teaching unpack! to read partitions directly
      ;; replaces this.
      "from-image"
      (do (when-not (and a b)
            (throw (ex-info "usage: disk slot <A|B> from-image <img> <img|blockdev>" {})))
          (fs/with-temp-dir [work {:prefix "ujima-from-image-"}]
            (let [tmp-pack (str (fs/path work "image.pack"))]
              (println "packing" a "-> temp pack")
              (pack/make! {:src a :out tmp-pack})
              (with-disk* b (fn [dev] (ab/install-into-slot! (->disk dev) tmp-pack slot)))))
          (println "installed" a "-> slot" (name slot) "on" b))

      "activate"
      (do (when (or (nil? a) b)
            (throw (ex-info "usage: disk slot <A|B> activate <img|blockdev>" {})))
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
