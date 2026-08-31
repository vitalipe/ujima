(ns ujima.device-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.device    :as device]
            [ujima.device.ab :as ab]))


;; init! = machine reality once at start. Stubs only — no OS.
;; Nothing here covers the hostname: it is derived in the initramfs before PID 1
;; (os/pipeline/base/identity/ujima-identity), so the runtime never sets it.


(deftest no-disk-answers-no-info
  (is (nil? (ab/ujima-disk-info nil)) "a query nil-puns; the writes still throw on nil"))


(deftest no-disk-is-left-alone
  (is (nil? (device/init! nil)) "a dev host has no ujima disk to stamp"))


(deftest a-real-disk-gets-its-id-stamped
  (let [stamped (atom 0)
        fake    (reify ab/UjimaSystemDisk
                  (system-disk-id! [_] (swap! stamped inc)))]
    (device/init! fake)
    (is (= 1 @stamped) "a nil disk above proves the guard; a real one stamps")))
