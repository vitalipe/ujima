(ns ujima.control.queries-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.control         :as control]
            [ujima.control.queries :as queries]))


(deftest audio-status-is-a-pure-settings-read
  (with-redefs [control/settings (constantly {[:audio :active]       :usb
                                              [:audio :usb :volume]  55
                                              [:audio :hdmi :volume] 70
                                              [:audio :muted]        false})]
    (is (= {:volume 55 :muted false :output :usb} (queries/audio-status))
        "volume follows the active class"))
  (with-redefs [control/settings (constantly {[:audio :active]      nil
                                              [:audio :usb :volume] 55
                                              [:audio :muted]       true})]
    (is (= {:volume nil :muted true :output nil} (queries/audio-status))
        "no active output -> nil volume, widgets grey out")))


(deftest keyboard-status-is-domain-facts-only
  (with-redefs [control/settings (constantly {[:keyboard :layout]            "tz"
                                              [:keyboard :available-layouts] ["us" "tz"]})]
    (is (= {:layout "tz" :layouts ["us" "tz"]} (queries/keyboard-status))
        "no presentation fields — :next lives in the UI projection")))
