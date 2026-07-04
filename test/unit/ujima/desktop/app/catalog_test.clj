(ns ujima.desktop.app.catalog-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.app.catalog :as catalog]))


(def ^:private raw
  {:apps [{:id :wikipedia :label "Wikipedia" :icon "wikipedia"
           :exec ["chromium" "--app=https://wikipedia.com" "--class=ujima-wikipedia"]
           :class "ujima-wikipedia"}
          {:id :write :label "Write" :icon "write"
           :exec ["libreoffice" "--writer"] :class "libreoffice-writer"}
          {:id :draw :label "Draw"
           :exec ["tuxpaint"] :class "TuxPaint.TuxPaint"}]})

(def ^:private cat (catalog/->catalog raw))


(deftest indexes-lower-cased-classes-to-app-maps
  ;; WM_CLASS casing varies by app — the adoption seed is lower-cased, values = full maps
  (is (= :draw      (get-in cat [:class->app "tuxpaint.tuxpaint" :id])))
  (is (= :wikipedia (get-in cat [:class->app "ujima-wikipedia" :id])))
  (is (= ["tuxpaint"] (get-in cat [:class->app "tuxpaint.tuxpaint" :exec]))))


(deftest listing-projects-in-order-with-icon-default
  (is (= [{:id :wikipedia :label "Wikipedia" :icon "wikipedia"}
          {:id :write     :label "Write"     :icon "write"}
          {:id :draw      :label "Draw"      :icon "draw"}]   ; :icon defaults to the id
         (catalog/listing cat))))


(deftest validates-loudly
  (is (thrown? clojure.lang.ExceptionInfo
        (catalog/->catalog {:apps [{:id :a :label "A" :class "x"}]}))
      "missing :exec")
  (is (thrown? clojure.lang.ExceptionInfo
        (catalog/->catalog {:apps [{:id :a :label "A" :exec ["x"]}]}))
      ":class is required — it is the adoption key")
  (is (thrown? clojure.lang.ExceptionInfo
        (catalog/->catalog {:apps [{:id :a :label "A" :exec ["x"] :class "x"}
                                   {:id :a :label "A2" :exec ["y"] :class "y"}]}))
      "duplicate ids")
  (is (thrown? clojure.lang.ExceptionInfo
        (catalog/->catalog {:apps [{:id :a :label "A" :exec ["x"] :class "Same"}
                                   {:id :b :label "B" :exec ["y"] :class "same"}]}))
      "shared WM_CLASS, case-insensitive"))
