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
         (try (commands/change-current-volume! "loud" :session) (catch Exception e (:error (ex-data e)))))))


(deftest change-keyboard-layout-accepts-only-available-codes
  (let [written (atom nil)]
    (with-redefs [control/settings  (constantly {[:keyboard :available-layouts] ["us" "tz"]})
                  control/settings! (fn [scope k v] (reset! written [scope k v]) {})]
      (is (= {:layout "tz"} (commands/change-keyboard-layout! "tz" :session)))
      (is (= [:session [:keyboard :layout] "tz"] @written))
      (is (= :keyboard/unknown-layout
             (try (commands/change-keyboard-layout! "fr" :session) (catch Exception e (:error (ex-data e))))))
      (is (= :request/malformed
             (try (commands/change-keyboard-layout! nil :session) (catch Exception e (:error (ex-data e)))))))))


;; ── change-setting!: the generic write, gated by the def ────────────────────

(defn- setting! [path value scope]
  (let [written (atom nil)]
    (with-redefs [control/settings! (fn [scope k v] (reset! written [scope k v]) {})]
      (try {:ack (commands/change-setting! path value scope) :wrote @written}
           (catch clojure.lang.ExceptionInfo e
             {:error (:error (ex-data e)) :message (ex-message e)})))))


(deftest change-setting-writes-what-the-defs-shape-allows
  (is (= {:ack {:value 55} :wrote [:session [:audio :usb :volume] 55]}
         (setting! [:audio :usb :volume] 55 :session)))
  (is (= :request/malformed (:error (setting! [:audio :usb :volume] "55" :session)))
      "a string where the shape says int is an error, not something to coerce")
  (is (= [:device [:system :hostname] "meru-01"]
         (:wrote (setting! [:system :hostname] "meru-01" :device))))
  (is (= [:device [:keyboard :available-layouts] ["us" "fr"]]
         (:wrote (setting! [:keyboard :available-layouts] ["us" "fr"] :device)))))


(deftest change-setting-refuses-with-the-defs-own-sentence
  (is (= "should be at most 100"
         (:message (setting! [:audio :usb :volume] 999 :session))))
  (is (= "not a timezone ujima knows"
         (:message (setting! [:system :timezone] "Mars/Olympus" :device)))
      "the pinned catalog is part of the shape")
  (is (= "hostname must be 1-16 letters, numbers or dashes"
         (:message (setting! [:system :hostname] "no spaces!" :device))))
  (is (= :request/malformed (:error (setting! [:audio :muted] "yes" :session)))))


(deftest change-setting-checks-the-path-and-the-scope
  (is (= :settings/unknown (:error (setting! [:no :such] "x" :session)))
      "not a setting — the addressing layer's answer, not a write")
  (let [{:keys [error message]} (setting! [:system :hostname] "meru-01" :session)]
    (is (= :request/malformed error) "hostname is device-only")
    (is (= "this setting takes device" message) "the def names the scopes it takes"))
  (is (= [:activity [:audio :muted] true] (:wrote (setting! [:audio :muted] true :activity)))
      "a scope the def does allow goes through"))


(deftest clear-scope-takes-any-defined-scope
  (let [cleared (atom nil)]
    (with-redefs [control/update-settings! (fn [scope f] (reset! cleared [scope (f {[:audio :muted] true})]) {})]
      (is (= {:cleared true} (commands/clear-scope! :device))
          "in-process callers may wipe any defined scope — runtime-only is the wire's gate")
      (is (= [:device {}] @cleared) "the whole scope map empties")
      (is (= :request/malformed
             (try (commands/clear-scope! :banana) (catch Exception e (:error (ex-data e)))))))))
