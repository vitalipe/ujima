(ns ujima.control-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [ujima.control :as control]
            [schema.ujima.settings :as defs]))


;; Full control-plane loop against real temp files. Control is a pure settings
;; machine — no OS stubs needed; converge ports are whatever the test attaches
;; with on-converge! (none, unless it's testing the notification itself).


(defn- fresh!
  ([] (fresh! []))
  ([targets]
   (let [dir (str (fs/create-temp-dir))]
     (control/init! {:storage dir :tmp dir})
     (doseq [t targets] (control/on-converge! t))
     dir)))

(defn- device-file [dir] (str dir "/device.edn"))

(defn- value [key] (:effective (control/setting key)))


(deftest defaults-when-nothing-is-stored
  (fresh!)
  (is (= 40 (value [:audio :usb :volume])))
  (is (= 70 (value [:audio :hdmi :volume])))
  (is (= "UjimaOS" (value [:system :name])) "the default names the machine — never nil"))


(deftest write-stamps-schema-and-round-trips
  (let [dir (fresh!)]
    (control/settings! :device [:audio :hdmi :volume] 85)
    (let [raw (edn/read-string (slurp (device-file dir)))]
      (is (= defs/schema (:schema raw)))
      (is (= 85 (get-in raw [:settings [:audio :hdmi :volume]]))))
    (is (= 85 (value [:audio :hdmi :volume])))))


(deftest scope-precedence-and-sibling-path-independence
  (fresh!)
  (control/settings! :device  [:audio :hdmi :volume] 85)
  (control/settings! :session [:audio :usb :volume] 20)
  (is (= 20 (value [:audio :usb :volume]))  "session overrides default")
  (is (= 85 (value [:audio :hdmi :volume])) "sibling path untouched by session write")
  (control/settings! :activity [:audio :usb :volume] 5)
  (is (= 5 (value [:audio :usb :volume])) "activity outranks session"))


(deftest write-of-non-scope-key-is-pruned
  (fresh!)
  (control/settings! :session [:system :name] "nope")   ; :device-only key
  (is (= "UjimaOS" (value [:system :name])) "pruned write leaves the default"))


(deftest file-with-matching-schema-loads
  (let [dir (fresh!)]
    (spit (device-file dir) (pr-str {:schema defs/schema
                                     :settings {[:audio :hdmi :volume] 90}}))
    (is (= 90 (value [:audio :hdmi :volume])))))


(deftest pre-schema-or-mismatched-file-is-ignored
  (let [dir (fresh!)]
    (spit (device-file dir) (pr-str {:settings {[:audio :hdmi :volume] 99}}))
    (is (= 70 (value [:audio :hdmi :volume])) "no :schema -> defaults")

    (spit (device-file dir) (pr-str {:schema -1 :settings {[:audio :hdmi :volume] 99}}))
    (is (= 70 (value [:audio :hdmi :volume])) "wrong :schema -> defaults")

    (spit (device-file dir) (pr-str {:settings {:audio/volume 55}}))
    (is (= 70 (value [:audio :hdmi :volume])) "old keyword format -> defaults")

    (spit (device-file dir) "42")
    (is (= 70 (value [:audio :hdmi :volume])) "non-map edn -> defaults")))


(deftest write-over-stale-file-replaces-it
  (let [dir (fresh!)]
    (spit (device-file dir) (pr-str {:settings {:audio/volume 55}}))   ; pre-schema content
    (control/settings! :device [:system :name] "meru-01")
    (let [raw (edn/read-string (slurp (device-file dir)))]
      (is (= defs/schema (:schema raw)))
      (is (= {[:system :name] "meru-01"} (:settings raw))
          "stale content discarded, not merged"))))


(deftest converge-targets-receive-effective-and-previous
  (let [seen  (atom [])
        vol   #(:effective (get % [:audio :hdmi :volume]))]
    (fresh! [(fn [s prv] (swap! seen conj [(vol s) (some-> prv vol)]))
             (fn [_ _] (throw (ex-info "boom" {})))])   ; must never break a converge
    (control/settings! :device [:audio :hdmi :volume] 85)
    (is (= [[85 70]] @seen) "write converge: (effective, previous-effective)")
    (is (= 85 (value [:audio :hdmi :volume])) "throwing target didn't break the write")
    (control/converge-fresh!)
    (is (= [[85 70] [85 nil]] @seen) "external converge: prv nil = assume nothing")))


(deftest records-carry-the-story-behind-the-value
  (fresh!)
  (control/settings! :session  [:keyboard :layout] "tz")
  (control/settings! :activity [:keyboard :layout] "il")
  (let [s (control/settings)]
    (is (= {:effective "il" :via :activity :default "us"
            :scopes {:device nil :session "tz" :activity "il"}}
           (get s [:keyboard :layout]))
        "the winner, why it won, what it falls back to, and what each scope holds")
    (is (= {:device nil} (:scopes (get s [:system :name])))
        "only the scopes the def allows — the keys are the write whitelist")
    (is (= :default (:via (get s [:audio :muted]))) "nothing set = the default stands")
    (is (= (count defs/settings) (count s)) "one record per setting, always")))


(deftest setting-is-one-key-out-of-settings
  (fresh!)
  (is (= (get (control/settings) [:audio :muted]) (control/setting [:audio :muted])))
  (is (nil? (control/setting [:audio :mooted])) "an undefined path reads nil"))
