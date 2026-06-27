(ns ujima.desktop.windows-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.catalog :as catalog]
            [ujima.desktop.windows :as w]))


(def cat
  (catalog/->catalog
    {:apps [{:id :launcher  :kind :shell   :label "Ujima"     :show-topbar? false :closable? false}
            {:id :wikipedia :kind :web     :label "Wikipedia" :url "http://x/wiki"
             :show-topbar? true :closable? true}
            {:id :write     :kind :desktop :label "Write"     :exec ["libreoffice"]
             :wm-class "libreoffice-writer" :show-topbar? true :closable? true}]}))

(def s0 (w/init-state cat))

(defn- play [events] (reduce w/apply-event s0 events))


(deftest new-window-creates-ujima-window-and-focuses-it
  (let [s (play [{:type :window/new :con-id 1 :class "ujima-wikipedia" :title "Wikipedia"}])
        snap (w/snapshot s)]
    (is (= "win-0001" (:current s)))
    (is (= 1 (count (:windows snap))))
    (is (= {:id "win-0001" :app-id :wikipedia :title "Wikipedia" :show-topbar true :closable true}
           (first (:windows snap))))))


(deftest desktop-window-tracked-by-natural-class
  (let [s (play [{:type :window/new :con-id 2 :class "libreoffice-writer" :title "Essay.odt"}])]
    (is (= :write (get-in s [:windows "win-0001" :app-id])))))


(deftest second-container-of-same-app-attaches-not-new-window
  ;; a save dialog / extra frame of a :single app joins the existing Ujima window
  (let [s (play [{:type :window/new :con-id 1 :class "ujima-wikipedia" :title "Wikipedia"}
                 {:type :window/new :con-id 3 :class "ujima-wikipedia" :title "Print"}])]
    (is (= 1 (count (:order s))) "still one Ujima window")
    (is (= #{1 3} (get-in s [:windows "win-0001" :wm-windows])))))


(deftest unmanaged-window-is-ignored
  (let [s (play [{:type :window/new :con-id 9 :class "Xmessage" :title "oops"}])]
    (is (empty? (:windows s)))
    (is (= :launcher (:current s)))))


(deftest close-keeps-workspace-until-last-container-gone
  (let [s (play [{:type :window/new :con-id 1 :class "ujima-wikipedia" :title "Wikipedia"}
                 {:type :window/new :con-id 3 :class "ujima-wikipedia" :title "Print"}
                 {:type :window/close :con-id 3}])]
    (is (= 1 (count (:order s))) "dialog closed, window survives")
    (is (= #{1} (get-in s [:windows "win-0001" :wm-windows])))))


(deftest close-last-container-removes-window-and-refocuses-launcher
  (let [s (play [{:type :window/new :con-id 1 :class "ujima-wikipedia" :title "Wikipedia"}
                 {:type :window/close :con-id 1}])]
    (is (empty? (:windows s)))
    (is (empty? (:order s)))
    (is (= :launcher (:current s)) "refocus launcher only after the confirmed close")))


(deftest crash-close-reconciles-like-a-graceful-one
  ;; an app-initiated/crash close arrives as the same :window/close event
  (let [s (play [{:type :window/new   :con-id 1 :class "ujima-wikipedia"}
                 {:type :window/new   :con-id 2 :class "libreoffice-writer"}
                 {:type :window/close :con-id 1}])]
    (is (= ["win-0002"] (:order s)))
    (is (nil? (get-in s [:wm->win 1])))))


(deftest title-event-updates-window-title
  (let [s (play [{:type :window/new   :con-id 1 :class "libreoffice-writer" :title ""}
                 {:type :window/title :con-id 1 :title "Essay.odt — LibreOffice Writer"}])]
    (is (= "Essay.odt — LibreOffice Writer" (get-in s [:windows "win-0001" :title])))))


(deftest focus-event-sets-current
  (let [s (play [{:type :window/new   :con-id 1 :class "ujima-wikipedia"}
                 {:type :window/new   :con-id 2 :class "libreoffice-writer"}
                 {:type :window/focus :con-id 1}])]
    (is (= "win-0001" (:current s)))))


(deftest snapshot-order-is-creation-order
  (let [s (play [{:type :window/new :con-id 1 :class "ujima-wikipedia"}
                 {:type :window/new :con-id 2 :class "libreoffice-writer"}])]
    (is (= [:wikipedia :write] (mapv :app-id (:windows (w/snapshot s)))))))
