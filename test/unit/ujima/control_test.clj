(ns ujima.control-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [ujima.control :as control]
            [ujima.control.defs :as defs]
            [ujima.control.reconcile :as reconcile]))


;; Full control-plane loop against real temp files, with the OS handlers stubbed out.


(defn- fresh! []
  (let [dir (str (fs/create-temp-dir))]
    (control/init! {:storage dir :tmp dir})
    dir))

(defn- device-file [dir] (str dir "/device.edn"))


(deftest defaults-when-nothing-is-stored
  (fresh!)
  (let [s (control/settings)]
    (is (= 40 (get s [:audio :usb :volume])))
    (is (= 70 (get s [:audio :hdmi :volume])))
    (is (= "ujima" (get s [:system :hostname])))))


(deftest write-stamps-schema-and-round-trips
  (with-redefs [reconcile/handlers {}]
    (let [dir (fresh!)]
      (control/settings! :device [:audio :hdmi :volume] 85)
      (let [raw (edn/read-string (slurp (device-file dir)))]
        (is (= defs/schema (:schema raw)))
        (is (= 85 (get-in raw [:settings [:audio :hdmi :volume]]))))
      (is (= 85 (get (control/settings) [:audio :hdmi :volume]))))))


(deftest scope-precedence-and-sibling-path-independence
  (with-redefs [reconcile/handlers {}]
    (fresh!)
    (control/settings! :device  [:audio :hdmi :volume] 85)
    (control/settings! :session [:audio :usb :volume] 20)
    (let [s (control/settings)]
      (is (= 20 (get s [:audio :usb :volume]))  "session overrides default")
      (is (= 85 (get s [:audio :hdmi :volume])) "sibling path untouched by session write"))
    (control/settings! :activity [:audio :usb :volume] 5)
    (is (= 5 (get (control/settings) [:audio :usb :volume])) "activity outranks session")))


(deftest write-of-non-scope-key-is-pruned
  (with-redefs [reconcile/handlers {}]
    (fresh!)
    (control/settings! :session [:system :hostname] "nope")   ; :device-only key
    (is (= "ujima" (get (control/settings) [:system :hostname])))))


(deftest file-with-matching-schema-loads
  (let [dir (fresh!)]
    (spit (device-file dir) (pr-str {:schema defs/schema
                                     :settings {[:audio :hdmi :volume] 90}}))
    (is (= 90 (get (control/settings) [:audio :hdmi :volume])))))


(deftest pre-schema-or-mismatched-file-is-ignored
  (let [dir (fresh!)]
    (spit (device-file dir) (pr-str {:settings {[:audio :hdmi :volume] 99}}))
    (is (= 70 (get (control/settings) [:audio :hdmi :volume])) "no :schema -> defaults")

    (spit (device-file dir) (pr-str {:schema -1 :settings {[:audio :hdmi :volume] 99}}))
    (is (= 70 (get (control/settings) [:audio :hdmi :volume])) "wrong :schema -> defaults")

    (spit (device-file dir) (pr-str {:settings {:audio/volume 55}}))
    (is (= 70 (get (control/settings) [:audio :hdmi :volume])) "old keyword format -> defaults")

    (spit (device-file dir) "42")
    (is (= 70 (get (control/settings) [:audio :hdmi :volume])) "non-map edn -> defaults")))


(deftest write-over-stale-file-replaces-it
  (with-redefs [reconcile/handlers {}]
    (let [dir (fresh!)]
      (spit (device-file dir) (pr-str {:settings {:audio/volume 55}}))   ; pre-schema content
      (control/settings! :device [:system :hostname] "meru-01")
      (let [raw (edn/read-string (slurp (device-file dir)))]
        (is (= defs/schema (:schema raw)))
        (is (= {[:system :hostname] "meru-01"} (:settings raw))
            "stale content discarded, not merged")))))
