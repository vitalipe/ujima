(ns ujima.linux.audio-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.linux.audio :as audio]))


;; pw-dump-shaped fixture: usb + hdmi sinks (usb is the default), one
;; unclassified sink, and the wireplumber default metadata.
(def ^:private fake-objs
  [{:id 99
    :props {:metadata.name "default"}
    :metadata [{:subject 0 :key "default.audio.sink"
                :value {:name "alsa_output.usb-Head-00.analog-stereo"}}]}
   {:id 60 :info {:props {:media.class "Audio/Sink"
                          :node.name   "alsa_output.usb-Head-00.analog-stereo"}}}
   {:id 51 :info {:props {:media.class "Audio/Sink"
                          :node.name   "alsa_output.platform-107c706400.hdmi.hdmi-stereo"}}}
   {:id 70 :info {:props {:media.class "Audio/Sink"
                          :node.name   "alsa_output.platform-bcm2835_audio.stereo-fallback"}}}])


(deftest output-class-classifies-by-node-name
  (is (= :hdmi (audio/output-class {:name "alsa_output.platform-107c706400.hdmi.hdmi-stereo"})))
  (is (= :usb  (audio/output-class {:name "alsa_output.usb-Logitech_H390-00.analog-stereo"})))
  (is (nil? (audio/output-class {:name "alsa_output.platform-bcm2835_audio.stereo-fallback"})))
  (is (nil? (audio/output-class {:name nil})))
  (is (nil? (audio/output-class {}))))


(deftest topology-is-the-cheap-name-and-class-read
  (with-redefs-fn {#'audio/pw-objects (constantly fake-objs)}
    #(is (= {:names   #{"alsa_output.usb-Head-00.analog-stereo"
                        "alsa_output.platform-107c706400.hdmi.hdmi-stereo"
                        "alsa_output.platform-bcm2835_audio.stereo-fallback"}
             :classes #{:usb :hdmi}}
            (audio/topology)))))


(deftest full-topology-snapshots-in-ujima-terms
  (with-redefs-fn {#'audio/pw-objects    (constantly fake-objs)
                   #'audio/volume-status (fn [id] (get {60 {:volume 40 :muted false}
                                                        51 {:volume 70 :muted true}} id))}
    #(is (= {:default :usb
             :sinks {:usb  {:id 60 :name "alsa_output.usb-Head-00.analog-stereo"
                            :volume 40 :muted false}
                     :hdmi {:id 51 :name "alsa_output.platform-107c706400.hdmi.hdmi-stereo"
                            :volume 70 :muted true}}}
            (audio/full-topology))
         "classified sinks only; per-sink volume+mute from one wpctl read each")))


(deftest full-topology-without-a-classified-default
  (with-redefs-fn {#'audio/pw-objects    (constantly (vec (remove #(= 99 (:id %)) fake-objs)))
                   #'audio/volume-status (constantly {:volume 50 :muted false})}
    #(is (nil? (:default (audio/full-topology))) "no default metadata -> nil default class")))


(deftest resolve-sink-normalizes-caller-shapes
  (with-redefs-fn {#'audio/pw-objects (constantly fake-objs)}
    #(do (is (= 60 (#'audio/resolve-sink {:id 60}))  "sink map -> id")
         (is (= 60 (#'audio/resolve-sink "alsa_output.usb-Head-00.analog-stereo")) "node name -> id")
         (is (= 42 (#'audio/resolve-sink 42))        "id passes through")
         (is (= "@DEFAULT_AUDIO_SINK@" (#'audio/resolve-sink "@DEFAULT_AUDIO_SINK@")) "@alias@ passes through")
         (is (nil? (#'audio/resolve-sink nil)))
         (is (thrown? Exception (#'audio/resolve-sink "no-such-node")))
         (is (thrown? Exception (#'audio/resolve-sink :usb)) "class addressing is gone — loud, not garbage-to-wpctl"))))
