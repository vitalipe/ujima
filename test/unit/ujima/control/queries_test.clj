(ns ujima.control.queries-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.control         :as control]
            [ujima.control.queries :as queries]
            [ujima.linux.audio     :as audio]))


(def ^:private usb-sink {:name "alsa_output.usb-Logitech_H390-00.analog-stereo"})


(deftest audio-status-reads-settings-not-hw
  (with-redefs [audio/default-sink (constantly usb-sink)
                control/settings   (constantly {[:audio :usb :volume]  55
                                                [:audio :hdmi :volume] 70
                                                [:audio :muted]        false})]
    (is (= {:volume 55 :muted false :output :usb} (queries/audio-status))))
  (with-redefs [audio/default-sink (constantly nil)
                control/settings   (constantly {[:audio :usb :volume] 55
                                                [:audio :muted]       true})]
    (is (= {:volume nil :muted true :output nil} (queries/audio-status))
        "no classifiable output -> nil volume, widgets grey out")))


(deftest keyboard-status-is-domain-facts-only
  (with-redefs [control/settings (constantly {[:keyboard :layout]            "tz"
                                              [:keyboard :available-layouts] ["us" "tz"]})]
    (is (= {:layout "tz" :layouts ["us" "tz"]} (queries/keyboard-status))
        "no presentation fields — :next lives in the UI projection")))
