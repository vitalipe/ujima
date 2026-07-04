(ns ujima.desktop.app.catalog-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.app.catalog :as catalog]))


(def ^:private raw
  {:apps [{:id :wikipedia :label "Wikipedia" :icon "wikipedia"
           :exec ["chromium" "--app=https://wikipedia.com"] :class-flag "--class"}
          {:id :write :label "Write" :icon "write"
           :exec ["libreoffice" "--writer"] :class "libreoffice-writer"}
          {:id :draw :label "Draw"
           :exec ["tuxpaint"] :class "TuxPaint.TuxPaint"}]})

(def ^:private cat (catalog/->catalog raw))


(deftest window-class-stamped-vs-natural
  (is (= "ujima-wikipedia"   (catalog/window-class {:id :wikipedia :class-flag "--class"})))
  (is (= "TuxPaint.TuxPaint" (catalog/window-class {:id :draw :class "TuxPaint.TuxPaint"}))))


(deftest indexes-lower-cased-classes
  ;; WM_CLASS casing varies by app — the adoption index is lower-cased
  (is (= :draw      (get-in cat [:class->id "tuxpaint.tuxpaint"])))
  (is (= :wikipedia (get-in cat [:class->id "ujima-wikipedia"]))))


(deftest listing-projects-in-order-with-icon-default
  (is (= [{:id :wikipedia :label "Wikipedia" :icon "wikipedia"}
          {:id :write     :label "Write"     :icon "write"}
          {:id :draw      :label "Draw"      :icon "draw"}]   ; :icon defaults to the id
         (catalog/listing cat))))


(deftest validates-loudly
  (is (thrown? clojure.lang.ExceptionInfo
        (catalog/->catalog {:apps [{:id :a :label "A"}]}))
      "missing :exec")
  (is (thrown? clojure.lang.ExceptionInfo
        (catalog/->catalog {:apps [{:id :a :label "A" :exec ["x"]}
                                   {:id :a :label "A2" :exec ["y"] :class "y"}]}))
      "duplicate ids")
  (is (thrown? clojure.lang.ExceptionInfo
        (catalog/->catalog {:apps [{:id :a :label "A" :exec ["x"] :class "X" :class-flag "--class"}]}))
      "both class sources")
  (is (thrown? clojure.lang.ExceptionInfo
        (catalog/->catalog {:apps [{:id :a :label "A" :exec ["x"] :class "Same"}
                                   {:id :b :label "B" :exec ["y"] :class "same"}]}))
      "shared WM_CLASS, case-insensitive"))
