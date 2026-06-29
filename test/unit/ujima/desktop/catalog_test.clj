(ns ujima.desktop.catalog-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.catalog :as catalog]))


(def raw
  {:apps [{:id :launcher :label "Ujima" :kind :shell :instance-policy :single
           :show-topbar? false :closable? false}
          {:id :wikipedia :label "Wikipedia" :kind :web :url "http://x/wiki"
           :instance-policy :single :show-topbar? true :closable? true}
          {:id :write :label "Write" :kind :desktop :exec ["libreoffice" "--writer"]
           :wm-class "libreoffice-writer" :instance-policy :single}]})

(def cat (catalog/->catalog raw))


(deftest ->catalog-indexes-by-id
  (is (= 3 (count (catalog/apps cat))))
  (is (= #{:launcher :wikipedia :write} (set (keys (:by-id cat)))))
  (is (= "Wikipedia" (:label (catalog/app cat :wikipedia))))
  (is (nil? (catalog/app cat :nope))))


(deftest ->catalog-rejects-bad-entries
  (is (thrown? Exception (catalog/->catalog {:apps [{:kind :shell}]}))               "missing :id")
  (is (thrown? Exception (catalog/->catalog {:apps [{:id :x :kind :spaceship}]}))    "unknown :kind")
  (is (thrown? Exception (catalog/->catalog {:apps [{:id :x :kind :web}]}))          "web without :url")
  (is (thrown? Exception (catalog/->catalog {:apps [{:id :x :kind :desktop}]}))      "desktop without :exec")
  (is (thrown? Exception (catalog/->catalog {:apps [{:id :x :kind :shell}
                                                    {:id :x :kind :shell}]}))        "duplicate :id"))


;; guards the real baked catalog (mirrors defs-schema-is-internally-consistent)
(deftest baked-catalog-loads-and-is-consistent
  (let [c (catalog/load! "assets/desktop/apps.edn")]
    (is (contains? (:by-id c) :launcher))
    (is (contains? (:by-id c) :wikipedia))
    (is (false? (:closable? (catalog/app c :launcher))))
    (is (= :single (:instance-policy (catalog/app c :write))))))
