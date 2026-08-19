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
  (is (= [{:id :wikipedia :label "Wikipedia" :icon "wikipedia" :category nil :hidden false}
          {:id :write     :label "Write"     :icon "write"     :category nil :hidden false}
          {:id :draw      :label "Draw"      :icon nil         :category nil :hidden false}]
         (catalog/listing cat))))


(deftest validates-loudly
  (is (thrown? clojure.lang.ExceptionInfo
        (catalog/->catalog {:apps [{:id :a}]}))
      "missing :label")
  (is (thrown? clojure.lang.ExceptionInfo
        (catalog/->catalog {:apps [{:id :a :label "A" :exec ["x"]}
                                   {:id :a :label "A2" :exec ["y"]}]}))
      "duplicate ids"))


(deftest validate-app-is-the-identity-core
  ;; kind-specific launchability is the loader's contract (validate-kind!) — the catalog
  ;; only asserts identity, so a kind-shaped spec without :exec is fine here
  (is (= {:id :a :label "A" :kind :link :url "http://x"}
         (catalog/validate-app! {:id :a :label "A" :kind :link :url "http://x"}))
      "identity-valid spec passes through")
  (is (thrown? clojure.lang.ExceptionInfo (catalog/validate-app! nil)) "non-map")
  (is (thrown? clojure.lang.ExceptionInfo (catalog/validate-app! {:id :a})) "missing :label"))
