(ns ujima.control.queries-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.control.queries :as queries]))


(deftest audio-status-follows-the-active-class
  (is (= {:volume 55 :muted false :output :usb}
         (queries/audio-status {[:audio :active]       :usb
                                [:audio :usb :volume]  55
                                [:audio :hdmi :volume] 70
                                [:audio :muted]        false})))
  (is (= {:volume nil :muted true :output nil}
         (queries/audio-status {[:audio :active]      nil
                                [:audio :usb :volume] 55
                                [:audio :muted]       true}))
      "no active output -> nil volume, widgets grey out"))


(deftest keyboard-status-is-domain-facts-only
  (is (= {:layout "tz" :layouts ["us" "tz"]}
         (queries/keyboard-status {[:keyboard :layout]            "tz"
                                   [:keyboard :available-layouts] ["us" "tz"]}))
      "no presentation fields — :next lives in the UI projection"))
