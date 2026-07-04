(ns ujima.control.commands-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.control          :as control]
            [ujima.control.commands :as commands]
            [ujima.linux.audio      :as audio]))


(def ^:private usb-sink {:name "alsa_output.usb-Logitech_H390-00.analog-stereo"})


(deftest change-current-volume-writes-clamped-value-to-current-class
  (let [written (atom nil)]
    (with-redefs [audio/default-sink (constantly usb-sink)
                  control/settings!  (fn [scope k v] (reset! written [scope k v]) {})]
      (is (= {:volume 100} (commands/change-current-volume! 250)) "clamped before storing; narrow ack")
      (is (= [:session [:audio :usb :volume] 100] @written))
      (is (= {:volume 42} (commands/change-current-volume! 42.6)) "coerced to int"))))


(deftest change-current-volume-rejects-when-no-output-classifies
  (with-redefs [audio/default-sink (constantly nil)]
    (is (= :audio/no-output
           (try (commands/change-current-volume! 50) (catch Exception e (:error (ex-data e))))))))


(deftest change-mute-writes-the-desired-state
  (let [written (atom nil)]
    (with-redefs [control/settings! (fn [scope k v] (reset! written [scope k v]) {})]
      (is (= {:muted true} (commands/change-mute! true)))
      (is (= [:session [:audio :muted] true] @written)))))


(deftest verbs-reject-malformed-values
  (is (= :request/malformed
         (try (commands/change-current-volume! "loud") (catch Exception e (:error (ex-data e))))))
  (is (= :request/malformed
         (try (commands/change-mute! "yes") (catch Exception e (:error (ex-data e)))))))


(deftest change-keyboard-layout-accepts-only-available-codes
  (let [written (atom nil)]
    (with-redefs [control/settings  (constantly {[:keyboard :available-layouts] ["us" "tz"]})
                  control/settings! (fn [scope k v] (reset! written [scope k v]) {})]
      (is (= {:layout "tz"} (commands/change-keyboard-layout! "tz")))
      (is (= [:session [:keyboard :layout] "tz"] @written))
      (is (= :keyboard/unknown-layout
             (try (commands/change-keyboard-layout! "zz") (catch Exception e (:error (ex-data e))))))
      (is (= :request/malformed
             (try (commands/change-keyboard-layout! nil) (catch Exception e (:error (ex-data e)))))))))
