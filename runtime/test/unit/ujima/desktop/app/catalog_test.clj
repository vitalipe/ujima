(ns ujima.desktop.app.catalog-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.app.catalog :as catalog]))


(def ^:private raw
  {:apps [{:id :wikipedia :label "Wikipedia" :icon "wikipedia"
           :exec ["chromium" "--app=https://wikipedia.com"]}
          {:id :write :label "Write" :icon "write" :exec ["libreoffice" "--writer"]}
          {:id :draw :label "Draw" :exec ["tuxpaint"]}]})

(def ^:private cat (catalog/->catalog raw))


(deftest indexes-by-id-abc
  (is (= [:draw :wikipedia :write] (:order cat)))
  (is (= ["tuxpaint"] (get-in cat [:by-id :draw :exec]))))


(deftest listing-projects-in-order-icon-as-resolved
  ;; :icon is whatever the loader resolved (a path on device) — no name defaulting here
  (is (= [{:id :draw      :label "Draw"      :icon nil         :category nil :hidden false}
          {:id :wikipedia :label "Wikipedia" :icon "wikipedia" :category nil :hidden false}
          {:id :write     :label "Write"     :icon "write"     :category nil :hidden false}]
         (catalog/listing cat))))


(deftest a-repeated-id-keeps-its-last-entry
  (let [c (catalog/->catalog {:apps [{:id :a :label "A" :exec ["x"] :class "A1"}
                                     {:id :b :label "B" :exec ["b"]}
                                     {:id :a :label "A2" :exec ["y"] :class "A2"}]})]
    (is (= [:a :b] (:order c)))
    (is (= "A2" (get-in c [:by-id :a :label])))
    (is (= {"A2" :a} (:by-class c)) "the dropped entry's class is gone too")))
