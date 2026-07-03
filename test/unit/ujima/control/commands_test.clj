(ns ujima.control.commands-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.control          :as control]
            [ujima.control.commands :as commands]
            [ujima.linux.audio      :as audio]))


(def ^:private usb-sink {:name "alsa_output.usb-Logitech_H390-00.analog-stereo"})


(deftest next-of-cycles-wraps-and-recovers
  (is (= "tz" (#'commands/next-of ["us" "tz"] "us")))
  (is (= "us" (#'commands/next-of ["us" "tz"] "tz"))     "wraps")
  (is (= "us" (#'commands/next-of ["us" "tz"] "il"))     "unknown current -> first")
  (is (= "us" (#'commands/next-of ["us"] "us"))          "single layout cycles to itself"))


(deftest set-volume-writes-clamped-value-to-current-class
  (let [written (atom nil)]
    (with-redefs [audio/default-sink (constantly usb-sink)
                  control/settings!  (fn [scope k v] (reset! written [scope k v]) {})]
      (is (= {:volume 100} (commands/set-volume! 250))   "clamped before storing")
      (is (= [:session [:audio :usb :volume] 100] @written))
      (is (= {:volume 42} (commands/set-volume! 42.6))   "coerced to int"))))


(deftest set-volume-rejects-when-no-output-classifies
  (with-redefs [audio/default-sink (constantly nil)]
    (is (= :audio/no-output
           (try (commands/set-volume! 50) (catch Exception e (:error (ex-data e))))))))


(deftest verbs-reject-malformed-values
  (is (= :request/malformed
         (try (commands/set-volume! "loud") (catch Exception e (:error (ex-data e))))))
  (is (= :request/malformed
         (try (commands/set-mute! "yes") (catch Exception e (:error (ex-data e)))))))


(deftest next-layout-advances-from-effective-settings
  (let [written (atom nil)]
    (with-redefs [control/settings  (constantly {[:keyboard :layout]            "us"
                                                 [:keyboard :available-layouts] ["us" "tz"]})
                  control/settings! (fn [scope k v] (reset! written [scope k v]) {})]
      (is (= {:layout "tz"} (commands/next-layout!)))
      (is (= [:session [:keyboard :layout] "tz"] @written))))
  (with-redefs [control/settings (constantly {[:keyboard :layout]            "us"
                                              [:keyboard :available-layouts] []})]
    (is (= :keyboard/no-layouts
           (try (commands/next-layout!) (catch Exception e (:error (ex-data e))))))))


(deftest audio-status-reads-settings-not-hw
  (with-redefs [audio/default-sink (constantly usb-sink)
                control/settings   (constantly {[:audio :usb :volume]  55
                                                [:audio :hdmi :volume] 70
                                                [:audio :muted]        false})]
    (is (= {:volume 55 :muted false :output :usb} (commands/audio-status))))
  (with-redefs [audio/default-sink (constantly nil)
                control/settings   (constantly {[:audio :usb :volume] 55
                                                [:audio :muted]       true})]
    (is (= {:volume nil :muted true :output nil} (commands/audio-status))
        "no classifiable output -> nil volume, widgets grey out")))
