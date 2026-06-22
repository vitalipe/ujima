(ns e2e.tests.rpi-deploy
  (:require [babashka.fs :as fs]

            [ujima.pack :as pack]
            [ujima.device.ab :as ab]
            [ujima.linux.disk :as linux-disk]
            [ujima.linux.disk.loop :as loopback]
            [ujima.linux.disk.mount :refer [with-mounted-vfat]]
            [lib.shell :refer [$! require-root!]]
            [ujima.device.ab.autoboot.bootfiles :as autoboot]
            [ujima.device.ab.autoboot :refer [->disk]]
            [ujima.device.ab.autoboot.partitions :as rpi-partitions]))

(defn test! [name f]
  (try
    (print (str "TEST " name " ... "))
    (flush)

    (let [result (f)]
      (if result
        (do
          (println "OK")
          true)

        (do
          (println "FAIL")
          (println " returned false")
          false)))

    (catch Throwable e
      (println "FAIL")
      (println " type:" (str (class e)))
      (println " message:" (or (ex-message e) "<none>"))
      (when-let [data (ex-data e)]
        (println " data:" data))
      (.printStackTrace e)
      false)))


(defn fail! [message data]
  (throw (ex-info message data)))


(defn assert= [message expected actual]
  (when-not (= expected actual)
    (fail! message {:expected expected :actual actual}))
  true)


(defn assert-nil! [message actual]
  (assert= message nil actual))


(defn assert-some! [message actual]
  (when-not (some? actual)
    (fail! message {:actual actual}))
  true)


(defn assert-block-device! [message path]
  (assert-some! message path)
  (when-not (linux-disk/block-device? path)
    (fail! message {:path path}))
  true)


(defn assert-installed-slot! [info slot expected-metadata]
  (let [installed (get-in info [:slots slot :ujima-os])]
    (assert-some! "Expected slot to have installed Ujima metadata"
                  installed)
    (assert= "Installed metadata should match pack metadata"
             expected-metadata
             (:metadata installed))
    (assert-some! "Installed metadata should include install details"
                  (:install installed))
    (assert-some! "Installed metadata should include :installed-at"
                  (get-in installed [:install :installed-at]))))


(defn assert-empty-slot! [info slot]
  (assert-nil! "Expected slot to be empty"
               (get-in info [:slots slot :ujima-os])))


(defn expected-root-partuuid [slot]
  (let [partition-number (case slot
                           :a 5
                           :b 6)]
    (format "PARTUUID=%08x-%02x"
            rpi-partitions/ujima-mbr-disk-id
            partition-number)))


(defn assert-slot-cmdline! [info slot]
  (let [boot-partition (get-in info [:slots slot :boot])]
    (assert-block-device! "Expected slot boot partition to be a block device"
                          boot-partition)
    (with-mounted-vfat [boot-mnt boot-partition]
      (assert= "Slot boot cmdline should point at the matching root partition"
               (expected-root-partuuid slot)
               (autoboot/cmdline boot-mnt)))))


(defn require-disk-info! [disk*]
  (or (ab/ujima-disk-info disk*)
      (fail! "Expected Ujima disk info" {})))


(defn assert-layout! [info device]
  (assert= "Disk info should report the loopback device"
           device
           (:device info))
  (doseq [path [(:config info)
                (:storage info)
                (get-in info [:slots :a :boot])
                (get-in info [:slots :a :root])
                (get-in info [:slots :b :boot])
                (get-in info [:slots :b :root])]]
    (assert-block-device! "Expected Ujima partition to be a block device"
                          path))
  true)


(defn test-initial-state! [disk*]
  (assert-nil! "Fresh disk image should not have a Ujima layout"
               (ab/ujima-disk-info disk*)))


(defn test-write-layout! [disk* device]
  (ab/write-ujima-layout! disk*)
  (let [info (require-disk-info! disk*)]
    (assert-layout! info device)
    (assert-empty-slot! info :a)
    (assert-empty-slot! info :b)
    (assert-nil! "Boot slot should not be set by layout creation"
                 (:boot-slot info))
    (assert-nil! "Try-boot slot should not be set by layout creation"
                 (:try-boot-slot info))))


(defn test-install! [disk* pack-file slot expected-installed-slots]
  (let [expected-metadata (pack/metadata pack-file)]
    (ab/install-into-slot! disk* pack-file slot)
    (let [info (require-disk-info! disk*)]
      (doseq [installed-slot expected-installed-slots]
        (assert-installed-slot! info installed-slot expected-metadata)
        (assert-slot-cmdline! info installed-slot))
      (doseq [empty-slot (remove expected-installed-slots [:a :b])]
        (assert-empty-slot! info empty-slot))
      true)))


(defn test-boot-slot! [disk* slot]
  (ab/set-boot-slot! disk* slot)
  (let [info (require-disk-info! disk*)]
    (assert= "Boot slot should match the requested slot"
             slot
             (:boot-slot info))
    (assert-nil! "Setting boot slot should clear try-boot"
                 (:try-boot-slot info))))


(defn test-try-boot-slot! [disk* boot-slot try-boot-slot]
  (ab/set-try-boot-slot! disk* try-boot-slot)
  (let [info (require-disk-info! disk*)]
    (assert= "Normal boot slot should be preserved"
             boot-slot
             (:boot-slot info))
    (assert= "Try-boot slot should match the requested slot"
             try-boot-slot
             (:try-boot-slot info))))


(defn test-clear-try-boot-slot! [disk* boot-slot]
  (ab/set-try-boot-slot! disk* nil)
  (let [info (require-disk-info! disk*)]
    (assert= "Normal boot slot should be preserved"
             boot-slot
             (:boot-slot info))
    (assert-nil! "Try-boot slot should be cleared"
                 (:try-boot-slot info))))


(defn run-tests! [tests]
  (reduce
    (fn [_ [name f]]
      (if (test! name f)
        true
        (reduced false)))
    true
    tests))


(defn run! [{tmp :tmp [pack-file] :args}]
  (let [sut-img-file (fs/path tmp "test-disk.img")
        pack-file    (or pack-file "assets/e2e/dummy.pack")]

    (require-root!)
    (pack/validate! pack-file)

    ($! truncate -s "32G" [sut-img-file])

    (loopback/with-loopback-device [device sut-img-file]
      (let [disk* (->disk {:device device})]
        (run-tests!
          [["0. initial state"
            #(test-initial-state! disk*)]

           ["1. initialize disk"
            #(test-write-layout! disk* device)]

           ["2. install into slot :a"
            #(test-install! disk* pack-file :a #{:a})]

           ["3. install into slot :b"
            #(test-install! disk* pack-file :b #{:a :b})]

           ["4. set boot slot :a"
            #(test-boot-slot! disk* :a)]

           ["5. set boot slot :b"
            #(test-boot-slot! disk* :b)]

           ["6. set try-boot slot :a"
            #(test-try-boot-slot! disk* :b :a)]

           ["7. clear try-boot slot"
            #(test-clear-try-boot-slot! disk* :b)]])))))
