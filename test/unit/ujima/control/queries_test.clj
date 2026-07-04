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


(deftest keyboard-status-publishes-the-cycle-as-next
  (with-redefs [control/settings (constantly {[:keyboard :layout]            "tz"
                                              [:keyboard :available-layouts] ["us" "tz"]})]
    (is (= {:layout "tz" :layouts ["us" "tz"] :next "us"} (queries/keyboard-status))
        "wraps"))
  (with-redefs [control/settings (constantly {[:keyboard :layout]            "il"
                                              [:keyboard :available-layouts] ["us" "tz"]})]
    (is (= "us" (:next (queries/keyboard-status))) "unknown current -> first"))
  (with-redefs [control/settings (constantly {[:keyboard :layout]            "us"
                                              [:keyboard :available-layouts] []})]
    (is (nil? (:next (queries/keyboard-status))) "no layouts -> no next")))


(deftest next-of-cycles-wraps-and-recovers
  (is (= "tz" (#'queries/next-of ["us" "tz"] "us")))
  (is (= "us" (#'queries/next-of ["us" "tz"] "tz")) "wraps")
  (is (= "us" (#'queries/next-of ["us" "tz"] "il")) "unknown current -> first")
  (is (= "us" (#'queries/next-of ["us"] "us"))      "single layout cycles to itself"))
