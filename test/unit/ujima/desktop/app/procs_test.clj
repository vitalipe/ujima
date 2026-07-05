(ns ujima.desktop.app.procs-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.app.procs :as procs]))


(def ^:private registry
  {:wikipedia {:handle :h1 :pid 100 :spawned-at 111}
   :write     {:handle :h2 :pid 200 :spawned-at 222 :windowed? true}})


(deftest mark-windowed-resolves-only-registered-apps
  (let [r (procs/mark-windowed registry #{:wikipedia :draw})]
    (is (true? (get-in r [:wikipedia :windowed?])) "its window has been seen")
    (is (nil? (get r :draw)) "windows without a spawn never create an entry")
    (is (= (:write registry) (:write r)) "already-resolved entries are untouched")))


(deftest awaiting-means-spawned-and-never-windowed
  (is (true? (procs/awaiting? registry :wikipedia)))
  (is (false? (boolean (procs/awaiting? registry :write)))
      "windowed once — a later windowless moment is the app CLOSED, not :new")
  (is (nil? (procs/awaiting? registry :draw)) "no spawn, nothing awaited"))
