(ns ujima.control.queries-test
  (:require [clojure.test :refer [deftest is testing]]
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


(deftest ordered-layouts-rotates-the-current-one-to-the-end
  (let [order* (fn [layout layouts]
                 (queries/ordered-layouts
                   (records {[:keyboard :layout]            layout
                             [:keyboard :available-layouts] layouts})))
        avail  ["us" "tz" "ma" "et" "cz"]]
    (is (= ["tz" "ma" "et" "cz" "us"] (order* "us" avail)))
    (is (= ["ma" "et" "cz" "us" "tz"] (order* "tz" avail)))
    (is (= ["us" "tz" "ma" "et" "cz"] (order* "cz" avail)) "the last one rotates to a no-op")

    (testing "a rotation, so the cyclic sequence is the same whichever is current"
      (let [cyclic (fn [xs] (let [i (.indexOf xs "us")] (concat (drop i xs) (take i xs))))]
        (is (apply = (map #(cyclic (order* % avail)) avail)))))

    (testing "the head is always what super+space advances to"
      (doseq [l avail]
        (is (= (first (order* l avail))
               (queries/next-keyboard-layout
                 (records {[:keyboard :layout] l [:keyboard :available-layouts] avail}))))))

    (is (= [] (order* "us" []))            "no layouts -> nothing to order")
    (is (= ["us" "tz"] (order* "fr" ["us" "tz"]))
        "a current layout that is not available leaves the list alone")))


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
