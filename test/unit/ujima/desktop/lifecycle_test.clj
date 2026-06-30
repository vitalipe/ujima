(ns ujima.desktop.lifecycle-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.lifecycle :as lc]))


(deftest open-running-forget
  (let [m (lc/open {} :write 1000)]
    (is (= :opening (get-in m [:write :state])))
    (is (lc/awaiting? m) "an :opening app gates the sync loop")
    (let [m (lc/running m :write)]
      (is (= :running (get-in m [:write :state])))
      (is (not (lc/awaiting? m)) ":running no longer gates the sync loop")
      (is (= {} (lc/forget m :write))))))


(deftest closing-then-forget
  (let [m (-> (lc/open {} :write 0) (lc/running :write) (lc/closing :write))]
    (is (= :closing (get-in m [:write :state])))
    (is (= {} (lc/forget m :write)))))


(deftest running-and-closing-are-noops-for-unknown-app
  (is (= {} (lc/running {} :ghost)))
  (is (= {} (lc/closing {} :ghost))))


(deftest awaiting-only-when-something-opening
  (is (not (lc/awaiting? {})))
  (is (lc/awaiting? (lc/open {} :write 0)))
  (is (not (lc/awaiting? (-> (lc/open {} :write 0) (lc/running :write))))))


(deftest expired-finds-stale-opening-only
  (let [m (-> (lc/open {} :write 0)       ; :opening at t=0
              (lc/open :files 9000))]     ; :opening at t=9000
    ;; at now=11000 with a 10000ms timeout: :write (11s old) expired, :files (2s old) not
    (is (= [:write] (vec (lc/expired m 11000 10000))))
    ;; a :running app is never "expired"
    (is (empty? (lc/expired (lc/running m :write) 11000 10000)))))
