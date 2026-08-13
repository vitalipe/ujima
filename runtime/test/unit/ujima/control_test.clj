(ns ujima.control-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [ujima.control :as control]
            [schema.ujima.settings :as defs]))


;; Full control-plane loop against real temp files. Control is a pure settings
;; machine — no OS stubs needed; converge ports are whatever :converge-targets
;; the test passes (none, unless it's testing the notification itself).


(defn- fresh!
  ([] (fresh! []))
  ([targets]
   (let [dir (str (fs/create-temp-dir))]
     (control/init! {:storage dir :tmp dir :converge-targets targets})
     dir)))

(defn- device-file [dir] (str dir "/device.edn"))


(deftest defaults-when-nothing-is-stored
  (fresh!)
  (let [s (control/settings)]
    (is (= 40 (get s [:audio :usb :volume])))
    (is (= 70 (get s [:audio :hdmi :volume])))
    (is (nil? (get s [:system :hostname])) "nil default = keep the baked /etc/hostname")))


(deftest write-stamps-schema-and-round-trips
  (let [dir (fresh!)]
    (control/settings! :device [:audio :hdmi :volume] 85)
    (let [raw (edn/read-string (slurp (device-file dir)))]
      (is (= defs/schema (:schema raw)))
      (is (= 85 (get-in raw [:settings [:audio :hdmi :volume]]))))
    (is (= 85 (get (control/settings) [:audio :hdmi :volume])))))


(deftest scope-precedence-and-sibling-path-independence
  (fresh!)
  (control/settings! :device  [:audio :hdmi :volume] 85)
  (control/settings! :session [:audio :usb :volume] 20)
  (let [s (control/settings)]
    (is (= 20 (get s [:audio :usb :volume]))  "session overrides default")
    (is (= 85 (get s [:audio :hdmi :volume])) "sibling path untouched by session write"))
  (control/settings! :activity [:audio :usb :volume] 5)
  (is (= 5 (get (control/settings) [:audio :usb :volume])) "activity outranks session"))


(deftest write-of-non-scope-key-is-pruned
  (fresh!)
  (control/settings! :session [:system :hostname] "nope")   ; :device-only key
  (is (nil? (get (control/settings) [:system :hostname])) "pruned write leaves the nil default"))


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
  (let [dir (fresh!)]
    (spit (device-file dir) (pr-str {:settings {:audio/volume 55}}))   ; pre-schema content
    (control/settings! :device [:system :hostname] "meru-01")
    (let [raw (edn/read-string (slurp (device-file dir)))]
      (is (= defs/schema (:schema raw)))
      (is (= {[:system :hostname] "meru-01"} (:settings raw))
          "stale content discarded, not merged"))))


(deftest converge-targets-receive-effective-and-previous
  (let [seen (atom [])]
    (fresh! [(fn [s prv] (swap! seen conj [(get s [:audio :hdmi :volume])
                                           (some-> prv (get [:audio :hdmi :volume]))]))
             (fn [_ _] (throw (ex-info "boom" {})))])   ; must never break a converge
    (control/settings! :device [:audio :hdmi :volume] 85)
    (is (= [[85 70]] @seen) "write converge: (effective, previous-effective)")
    (is (= 85 (get (control/settings) [:audio :hdmi :volume])) "throwing target didn't break the write")
    (control/converge-fresh!)
    (is (= [[85 70] [85 nil]] @seen) "external converge: prv nil = assume nothing")))


(deftest records-carry-the-story-behind-the-value
  (fresh!)
  (control/settings! :session  [:keyboard :layout] "tz")
  (control/settings! :activity [:keyboard :layout] "il")
  (let [tree (control/settings-records-tree)]
    (is (= {:effective "il" :via :activity :default "us"
            :scopes {:device nil :session "tz" :activity "il"}}
           (get-in tree [:keyboard :layout]))
        "the winner, why it won, what it falls back to, and what each scope holds")
    (is (= {:device nil} (:scopes (get-in tree [:system :hostname])))
        "only the scopes the def allows — the keys are the write whitelist")
    (is (= :default (:via (get-in tree [:audio :muted]))) "nothing set = the default stands")
    (is (= #{:active :muted :usb :hdmi} (set (keys (:audio tree))))
        "a path key nests — [:audio :usb :volume] is three levels, not one")))


(deftest records-and-settings-cannot-disagree
  (fresh!)
  (control/settings! :device   [:audio :usb :volume] 85)
  (control/settings! :activity [:audio :muted]       true)
  (let [flat (control/settings)
        tree (control/settings-records-tree)]
    (is (= flat (into {} (map (fn [[key _]] [key (:effective (get-in tree key))])) flat))
        "one merge, two renders")))
