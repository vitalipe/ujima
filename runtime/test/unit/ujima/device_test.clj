(ns ujima.device-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.device           :as device]
            [ujima.device.ab        :as ab]
            [ujima.linux.system     :as system]
            [ujima.linux.devicetree :as devicetree]))


;; init! = machine reality once at start. Stubs only — no OS.


(deftest hostname-derives-from-the-serial
  (let [applied (atom nil)]
    (with-redefs [devicetree/serial-tail (constantly "0951")
                  system/hostname        (constantly "old-name")
                  system/hostname!       (fn [v] (reset! applied v))]
      (device/init! nil))
    (is (= "ujima-0951" @applied))))


(deftest hostname-in-sync-is-left-alone
  (with-redefs [devicetree/serial-tail (constantly "0951")
                system/hostname        (constantly "ujima-0951")
                system/hostname!       (fn [_] (throw (ex-info "unexpected" {})))]
    (is (nil? (device/init! nil)) "the derived name already holds — nothing to apply")))


(deftest no-devicetree-leaves-the-host-alone
  (with-redefs [devicetree/serial-tail (constantly nil)
                system/hostname!       (fn [_] (throw (ex-info "unexpected" {})))]
    (is (nil? (device/init! nil)) "x86 dev hosts keep their own name")))


(deftest no-disk-answers-no-info
  (is (nil? (ab/ujima-disk-info nil)) "a query nil-puns; the writes still throw on nil"))


(deftest a-real-disk-gets-its-id-stamped
  (let [stamped (atom 0)
        fake    (reify ab/UjimaSystemDisk
                  (system-disk-id! [_] (swap! stamped inc)))]
    (with-redefs [devicetree/serial-tail (constantly nil)]
      (device/init! fake))
    (is (= 1 @stamped) "a nil disk above proves the guard; a real one stamps")))
