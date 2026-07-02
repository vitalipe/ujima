(ns ujima.linux.audio-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.linux.audio :as audio]))


(def ^:private fake-sinks
  [{:id 51 :name "alsa_output.platform-107c706400.hdmi.hdmi-stereo"}
   {:id 60 :name "alsa_output.usb-Logitech_H390-00.analog-stereo"}
   {:id 61 :name "alsa_output.usb-Other_Headset-00.analog-stereo"}])


(deftest output-class-classifies-by-node-name
  (is (= :hdmi (audio/output-class {:name "alsa_output.platform-107c706400.hdmi.hdmi-stereo"})))
  (is (= :usb  (audio/output-class {:name "alsa_output.usb-Logitech_H390-00.analog-stereo"})))
  (is (nil? (audio/output-class {:name "alsa_output.platform-bcm2835_audio.stereo-fallback"})))
  (is (nil? (audio/output-class {:name nil})))
  (is (nil? (audio/output-class {}))))


(deftest class->sink-picks-first-match-and-nil-when-absent
  (with-redefs [audio/sinks (constantly fake-sinks)]
    (is (= 51 (:id (audio/class->sink :hdmi))))
    (is (= 60 (:id (audio/class->sink :usb)))))
  (with-redefs [audio/sinks (constantly [])]
    (is (nil? (audio/class->sink :usb)))))


(deftest resolve-sink-normalizes-every-caller-shape
  (with-redefs [audio/sinks (constantly fake-sinks)]
    (is (= 60 (#'audio/resolve-sink :usb))                 "class keyword -> id")
    (is (= 51 (#'audio/resolve-sink (first fake-sinks)))   "sink map -> id")
    (is (= 60 (#'audio/resolve-sink "alsa_output.usb-Logitech_H390-00.analog-stereo")) "node name -> id")
    (is (= 42 (#'audio/resolve-sink 42))                   "id passes through")
    (is (= "@DEFAULT_AUDIO_SINK@" (#'audio/resolve-sink "@DEFAULT_AUDIO_SINK@")) "@alias@ passes through")
    (is (thrown? Exception (#'audio/resolve-sink "no-such-node-name")))
    (with-redefs [audio/sinks (constantly [])]
      (is (thrown? Exception (#'audio/resolve-sink :usb)) "absent class is loud"))))
