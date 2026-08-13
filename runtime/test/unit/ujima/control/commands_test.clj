(ns ujima.control.commands-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.control          :as control]
            [ujima.control.commands :as commands]))


(deftest change-current-volume-targets-the-active-class
  (let [written (atom nil)]
    (with-redefs [control/settings  (constantly {[:audio :active] :usb})
                  control/settings! (fn [scope k v] (reset! written [scope k v]) {})]
      (is (= {:volume 100} (commands/change-current-volume! 250 :session)) "clamped before storing; narrow ack")
      (is (= [:session [:audio :usb :volume] 100] @written))
      (is (= {:volume 42} (commands/change-current-volume! 42.6 :session)) "coerced to int"))))


(deftest change-current-volume-rejects-without-an-active-output
  (with-redefs [control/settings (constantly {})]
    (is (= :audio/no-output
           (try (commands/change-current-volume! 50 :session) (catch Exception e (:error (ex-data e))))))))


(deftest change-mute-writes-the-desired-state
  (let [written (atom nil)]
    (with-redefs [control/settings! (fn [scope k v] (reset! written [scope k v]) {})]
      (is (= {:muted true} (commands/change-mute! true :session)))
      (is (= [:session [:audio :muted] true] @written)))))


(deftest change-active-output-normalizes-and-validates
  (let [written (atom nil)]
    (with-redefs [control/settings! (fn [scope k v] (reset! written [scope k v]) {})]
      (is (= {:output :usb} (commands/change-active-output! "usb" :session)) "strings normalize to keywords")
      (is (= [:session [:audio :active] :usb] @written))
      (is (= {:output nil} (commands/change-active-output! nil :session)) "nil = no output, allowed")
      (is (= [:session [:audio :active] nil] @written))
      (is (= :request/malformed
             (try (commands/change-active-output! "surround9000" :session)
                  (catch Exception e (:error (ex-data e)))))))))


(deftest verbs-reject-malformed-values
  (is (= :request/malformed
         (try (commands/change-current-volume! "loud" :session) (catch Exception e (:error (ex-data e))))))
  (is (= :request/malformed
         (try (commands/change-mute! "yes" :session) (catch Exception e (:error (ex-data e)))))))


(deftest change-keyboard-layout-accepts-only-available-codes
  (let [written (atom nil)]
    (with-redefs [control/settings  (constantly {[:keyboard :available-layouts] ["us" "tz"]})
                  control/settings! (fn [scope k v] (reset! written [scope k v]) {})]
      (is (= {:layout "tz"} (commands/change-keyboard-layout! "tz" :session)))
      (is (= [:session [:keyboard :layout] "tz"] @written))
      (is (= :keyboard/unknown-layout
             (try (commands/change-keyboard-layout! "zz" :session) (catch Exception e (:error (ex-data e))))))
      (is (= :request/malformed
             (try (commands/change-keyboard-layout! nil :session) (catch Exception e (:error (ex-data e)))))))))
