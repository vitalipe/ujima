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
  (let [c (catalog/->catalog {:apps [{:id :a :label "A" :exec ["x"] :window {:class "A1"}}
                                     {:id :b :label "B" :exec ["b"]}
                                     {:id :a :label "A2" :exec ["y"] :window {:class "A2"}}]})]
    (is (= [:a :b] (:order c)))
    (is (= "A2" (get-in c [:by-id :a :label])))
    (is (= [[{:class "A2"} :a]] (:windows c)) "the dropped entry's window is gone too")))


(deftest a-window-goes-to-the-app-that-describes-it
  (let [c (catalog/->catalog {:apps [{:id :paint :label "Paint" :exec ["p"] :window {:class "TuxPaint"}}
                                     {:id :sky   :label "Sky"   :exec ["s"]}]})]
    (is (= :paint (catalog/app-of-window c {:class "TuxPaint" :title "untitled"}))
        "properties the app does not name are not looked at")
    (is (nil? (catalog/app-of-window c {:class "Gimp"})))
    (is (nil? (catalog/app-of-window c {:title "Sky"})) "an app naming nothing claims nothing")))
