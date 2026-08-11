(ns ujima.schema-test
  "conform! against the real data plane — shapes, coercion, the catalogs."
  (:require [clojure.test :refer [deftest is]]
            [ujima.schema :as schema]))


(defn- rejection [path value]
  (try (schema/conform! path value) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))


(deftest conforms-and-coerces
  (is (= 55 (schema/conform! [:audio :usb :volume] 55)))
  (is (= 55 (schema/conform! [:audio :usb :volume] "55")) "wire strings decode")
  (is (= :usb (schema/conform! [:audio :active] "usb")) "class strings become keywords")
  (is (= true (schema/conform! [:audio :muted] true)))
  (is (= "pi-nyati" (schema/conform! [:system :hostname] "pi-nyati"))))


(deftest catalogs-answer-from-the-pins
  (is (= "Africa/Nairobi" (schema/conform! [:system :timezone] "Africa/Nairobi")))
  (is (= "not a timezone ujima knows" (rejection [:system :timezone] "Mars/Olympus")))
  (is (= ["us" "fr"] (schema/conform! [:keyboard :available-layouts] ["us" "fr"])))
  (is (= "not an XKB layout ujima knows" (rejection [:keyboard :available-layouts] ["us" "xx"]))
      "each code checks against the pinned catalog"))


(deftest refuses-with-a-human-sentence
  (is (= "should be at most 100" (rejection [:audio :usb :volume] 999)))
  (is (= "hostname must be 1-16 letters, numbers or dashes" (rejection [:system :hostname] "no spaces!")))
  (is (= :request/malformed (try (schema/conform! [:audio :muted] "yes")
                                 (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))))


(deftest unknown-paths-pass-through
  (is (= "x" (schema/conform! [:no :such] "x")) "the addressing layer owns that 404"))
