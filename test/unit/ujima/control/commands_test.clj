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


(deftest set-volume-writes-clamped-value-and-returns-the-audio-resource
  (let [written (atom nil)
        store   (atom {[:audio :muted] false})]
    (with-redefs [audio/default-sink (constantly usb-sink)
                  control/settings   (fn [] @store)
                  control/settings!  (fn [scope k v]
                                       (reset! written [scope k v])
                                       (swap! store assoc k v))]
      (is (= {:volume 100 :muted false :output :usb} (commands/set-volume! 250))
          "clamped before storing; write returns the fresh resource")
      (is (= [:session [:audio :usb :volume] 100] @written))
      (is (= 42 (:volume (commands/set-volume! 42.6))) "coerced to int"))))


(deftest set-mute-returns-the-audio-resource
  (let [store (atom {[:audio :usb :volume] 55 [:audio :muted] false})]
    (with-redefs [audio/default-sink (constantly usb-sink)
                  control/settings   (fn [] @store)
                  control/settings!  (fn [_ k v] (swap! store assoc k v))]
      (is (= {:volume 55 :muted true :output :usb} (commands/set-mute! true))))))


(deftest set-volume-rejects-when-no-output-classifies
  (with-redefs [audio/default-sink (constantly nil)]
    (is (= :audio/no-output
           (try (commands/set-volume! 50) (catch Exception e (:error (ex-data e))))))))


(deftest verbs-reject-malformed-values
  (is (= :request/malformed
         (try (commands/set-volume! "loud") (catch Exception e (:error (ex-data e))))))
  (is (= :request/malformed
         (try (commands/set-mute! "yes") (catch Exception e (:error (ex-data e)))))))


(deftest keyboard-status-publishes-the-cycle-as-next
  (with-redefs [control/settings (constantly {[:keyboard :layout]            "tz"
                                              [:keyboard :available-layouts] ["us" "tz"]})]
    (is (= {:layout "tz" :layouts ["us" "tz"] :next "us"} (commands/keyboard-status))
        "wraps"))
  (with-redefs [control/settings (constantly {[:keyboard :layout]            "il"
                                              [:keyboard :available-layouts] ["us" "tz"]})]
    (is (= "us" (:next (commands/keyboard-status))) "unknown current -> first"))
  (with-redefs [control/settings (constantly {[:keyboard :layout]            "us"
                                              [:keyboard :available-layouts] []})]
    (is (nil? (:next (commands/keyboard-status))) "no layouts -> no next")))


(deftest set-layout-accepts-only-available-codes-and-returns-the-keyboard-resource
  (let [written (atom nil)
        store   (atom {[:keyboard :layout]            "us"
                       [:keyboard :available-layouts] ["us" "tz"]})]
    (with-redefs [control/settings  (fn [] @store)
                  control/settings! (fn [scope k v]
                                      (reset! written [scope k v])
                                      (swap! store assoc k v))]
      (is (= {:layout "tz" :layouts ["us" "tz"] :next "us"} (commands/set-layout! "tz")))
      (is (= [:session [:keyboard :layout] "tz"] @written))
      (is (= :keyboard/unknown-layout
             (try (commands/set-layout! "zz") (catch Exception e (:error (ex-data e))))))
      (is (= :request/malformed
             (try (commands/set-layout! nil) (catch Exception e (:error (ex-data e)))))))))


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
