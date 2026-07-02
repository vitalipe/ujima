(ns ujima.linux.desktop-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.linux.desktop :as desktop]))


(deftest output-class-classifies-by-node-name
  (is (= :hdmi (desktop/output-class {:name "alsa_output.platform-107c706400.hdmi.hdmi-stereo"})))
  (is (= :usb  (desktop/output-class {:name "alsa_output.usb-Logitech_H390-00.analog-stereo"})))
  (is (nil? (desktop/output-class {:name "alsa_output.platform-bcm2835_audio.stereo-fallback"})))
  (is (nil? (desktop/output-class {:name nil})))
  (is (nil? (desktop/output-class {}))))


(deftest sink-for-class-picks-first-match-and-nil-when-absent
  (with-redefs [desktop/sinks (constantly
                                [{:id 51 :name "alsa_output.platform-107c706400.hdmi.hdmi-stereo"}
                                 {:id 60 :name "alsa_output.usb-Logitech_H390-00.analog-stereo"}
                                 {:id 61 :name "alsa_output.usb-Other_Headset-00.analog-stereo"}])]
    (is (= 51 (:id (desktop/sink-for-class :hdmi))))
    (is (= 60 (:id (desktop/sink-for-class :usb)))))
  (with-redefs [desktop/sinks (constantly [])]
    (is (nil? (desktop/sink-for-class :usb)))))
