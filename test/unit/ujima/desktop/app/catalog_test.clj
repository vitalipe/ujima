(ns ujima.desktop.app.catalog-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.app.catalog :as catalog]))


(def ^:private raw
  {:apps [{:id :wikipedia :label "Wikipedia" :icon "wikipedia"
           :exec ["chromium" "--app=https://wikipedia.com"]}
          {:id :write :label "Write" :icon "write" :exec ["libreoffice" "--writer"]}
          {:id :draw :label "Draw" :exec ["tuxpaint"]}]})

(def ^:private cat (catalog/->catalog raw))


(deftest indexes-by-id-in-order
  (is (= [:wikipedia :write :draw] (:order cat)))
  (is (= ["tuxpaint"] (get-in cat [:by-id :draw :exec]))))


(deftest listing-projects-in-order-icon-as-resolved
  ;; :icon is whatever the loader resolved (a path on device) — no name defaulting here
  (is (= [{:id :wikipedia :label "Wikipedia" :icon "wikipedia" :category nil}
          {:id :write     :label "Write"     :icon "write"     :category nil}
          {:id :draw      :label "Draw"      :icon nil         :category nil}]
         (catalog/listing cat))))


(deftest validates-loudly
  (is (thrown? clojure.lang.ExceptionInfo
        (catalog/->catalog {:apps [{:id :a :label "A"}]}))
      "missing :exec")
  (is (thrown? clojure.lang.ExceptionInfo
        (catalog/->catalog {:apps [{:id :a :label "A" :exec ["x"]}
                                   {:id :a :label "A2" :exec ["y"]}]}))
      "duplicate ids"))


(deftest validate-app-is-the-per-app-contract
  (is (= {:id :a :label "A" :exec ["x"]}
         (catalog/validate-app! {:id :a :label "A" :exec ["x"]})) "valid spec passes through")
  (is (thrown? clojure.lang.ExceptionInfo (catalog/validate-app! nil)) "non-map")
  (is (thrown? clojure.lang.ExceptionInfo (catalog/validate-app! {:id :a :label "A" :exec []}))
      "empty :exec"))
