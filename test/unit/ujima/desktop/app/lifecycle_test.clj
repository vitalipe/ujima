(ns ujima.desktop.app.lifecycle-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.app.catalog   :as catalog]
            [ujima.desktop.app.windows   :as windows]
            [ujima.desktop.app.lifecycle :as lc]))


(def ^:private cat
  (catalog/->catalog
    {:apps [{:id :wikipedia :label "Wikipedia" :icon "wikipedia"
             :exec ["chromium" "--app=https://wikipedia.com" "--class=ujima-wikipedia"]
             :class "ujima-wikipedia"}
            {:id :write :label "Write" :icon "write"
             :exec ["libreoffice" "--writer"] :class "libreoffice-writer"}]}))


(def ^:private wiki-win
  {:con-id 42 :class "ujima-wikipedia" :title "Wikipedia" :focused? true
   :workspace "wikipedia" :floating? false :transient? false})


(deftest the-app-sm-is-a-pure-join
  (let [view (lc/view cat [wiki-win] {:write {:handle :h :pid 9 :spawned-at 111}})]
    (is (= :running (lc/state-of view :wikipedia)) "windows present")
    (is (= :new (lc/state-of view :write)) "spawn registered, no window yet"))
  (let [view (lc/view cat [] {:write {:handle :h :pid 9 :spawned-at 111 :windowed? true}})]
    (is (= :closed (lc/state-of view :write))
        "windowed once, windows gone — closed, never :new again"))
  (is (= :closed (lc/state-of (lc/view cat [] {}) :wikipedia)) "nothing anywhere"))


(deftest focus-resolves-to-the-owning-app
  (let [view (lc/view cat [wiki-win] {})]
    (is (= :wikipedia (:current view)))
    (is (= 42 (get-in view [:focused :con-id])) "the raw fact rides along for the close verb"))
  (let [eww  {:con-id 3 :class "Eww" :title "Eww - launcher" :focused? true
              :workspace "1" :floating? false :transient? false}
        view (lc/view cat [eww] {})]
    (is (nil? (:current view)) "an unmanaged focus is nobody's")))


(deftest titles-prefer-the-focused-window
  (let [docs [{:con-id 9 :class "libreoffice-writer" :title "Essay.odt" :focused? false
               :workspace "write" :floating? false :transient? false}
              {:con-id 10 :class "libreoffice-writer" :title "Notes.odt" :focused? true
               :workspace "write" :floating? false :transient? false}]
        view (lc/view cat docs {})]
    (is (= "Notes.odt" (:title (second (:apps view)))))))


(deftest snapshot-is-the-wire-shape
  (is (= {:apps [{:id :wikipedia :label "Wikipedia" :icon "wikipedia" :category nil
                  :state :running :title "Wikipedia"}]
          :current :wikipedia
          :current-title "Wikipedia"}
         (lc/snapshot (lc/view cat [wiki-win] {}))))
  (is (= {:apps [] :current nil :current-title nil}
         (lc/snapshot (lc/view cat [] {})))
      ":closed apps stay off the wire"))
