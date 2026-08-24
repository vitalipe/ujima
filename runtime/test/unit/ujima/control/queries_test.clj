(ns ujima.control.queries-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [ujima.control.queries :as queries]))


(defn- records [m] (update-vals m #(hash-map :effective %)))


(deftest audio-status-follows-the-active-class
  (is (= {:volume 55 :muted false :output :usb}
         (queries/audio-status (records {[:audio :active]       :usb
                                         [:audio :usb :volume]  55
                                         [:audio :hdmi :volume] 70
                                         [:audio :muted]        false}))))
  (is (= {:volume nil :muted true :output nil}
         (queries/audio-status (records {[:audio :active]      nil
                                         [:audio :usb :volume] 55
                                         [:audio :muted]       true})))
      "no active output -> nil volume, widgets grey out"))


(deftest public-settings-drops-the-secrets
  (let [public (queries/public-settings (records {[:circle :token]      "deadbeef"
                                                  [:audio :muted]       false
                                                  [:system :name]       "meru-01"}))]
    (is (= #{[:audio :muted] [:system :name]} (set (keys public))))
    (is (not (str/includes? (pr-str public) "deadbeef")) "the record goes, not just the value")))


(deftest next-keyboard-layout-cycles-the-available-ones
  (let [cycle* (fn [layout layouts]
                 (queries/next-keyboard-layout
                   (records {[:keyboard :layout]            layout
                             [:keyboard :available-layouts] layouts})))]
    (is (= "tz" (cycle* "us" ["us" "tz"])))
    (is (= "us" (cycle* "tz" ["us" "tz"]))  "wraps")
    (is (= "us" (cycle* "il" ["us" "tz"]))  "unknown current -> first")
    (is (nil?   (cycle* "us" []))           "no layouts -> no next")))


(deftest settings->tree-nests-the-path-keys
  (is (= {:audio {:usb  {:volume {:effective 40}}
                  :muted        {:effective false}}
          :system {:name        {:effective "meru-01"}}}
         (queries/settings->tree (records {[:audio :usb :volume] 40
                                           [:audio :muted]       false
                                           [:system :name]       "meru-01"})))
      "[:audio :usb :volume] is three levels, not one"))
