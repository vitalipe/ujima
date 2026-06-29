(ns ujima.desktop.windows-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.catalog :as catalog]
            [ujima.desktop.windows :as w]))


(def cat
  (catalog/->catalog
    {:apps [{:id :launcher  :kind :shell   :label "Ujima"     :show-topbar? false :closable? false}
            {:id :wikipedia :kind :web     :label "Wikipedia" :url "http://x/wiki" :icon "globe"
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
    (is (= {:id "win-0001" :app-id :wikipedia :title "Wikipedia" :icon "globe" :show-topbar true :closable true}
           (first (:windows snap))))))


(deftest desktop-window-tracked-by-natural-class
  (let [s (play [{:type :window/new :con-id 2 :class "libreoffice-writer" :title "Essay.odt"}])]
    (is (= :write (get-in s [:windows "win-0001" :app-id])))))


(deftest class-match-is-case-insensitive
  ;; i3 reports e.g. "TuxPaint"/"Libreoffice-Writer"; the catalog wm-class may be lower-case
  (let [s (play [{:type :window/new :con-id 5 :class "Libreoffice-Writer" :title "Essay"}])]
    (is (= :write (get-in s [:windows "win-0001" :app-id])))))


(deftest late-class-adopted-on-title
  ;; LibreOffice maps its doc window before setting WM_CLASS — the class only arrives on a later
  ;; title event, so the window must be adopted then (not only at :window/new)
  (let [s (play [{:type :window/new   :con-id 1 :class nil :title "LibreOffice"}
                 {:type :window/title :con-id 1 :class "libreoffice-writer"
                  :title "Untitled 1 — LibreOffice Writer"}])]
    (is (= :write     (get-in s [:windows "win-0001" :app-id])))
    (is (= "win-0001" (:current s)))
    (is (= "Untitled 1 — LibreOffice Writer" (get-in s [:windows "win-0001" :title])))))


(deftest transient-with-matching-class-not-adopted-as-primary
  ;; LibreOffice's Tip-of-the-Day is born with the writer class but is a dialog — it must not
  ;; become the primary Write window
  (let [s (play [{:type :window/new :con-id 2 :class "libreoffice-writer" :transient? true
                  :title "Tip of the Day"}])]
    (is (empty? (:windows s)))
    (is (= :launcher (:current s)))))


(deftest transient-attaches-to-already-tracked-app
  ;; once the app's primary window is tracked, its dialog (same class) joins it
  (let [s (play [{:type :window/new :con-id 1 :class "libreoffice-writer" :title "Untitled 1"}
                 {:type :window/new :con-id 2 :class "libreoffice-writer" :transient? true :title "Tip"}])]
    (is (= 1 (count (:order s))) "still one Ujima window")
    (is (= #{1 2} (get-in s [:windows "win-0001" :wm-windows])))))


(deftest app-for-class-matches-catalog-case-insensitively
  ;; the reconcile loop uses this to test a live get_tree window against the catalog
  (is (= :write     (w/app-for-class s0 "libreoffice-writer")))
  (is (= :write     (w/app-for-class s0 "Libreoffice-Writer")))
  (is (= :wikipedia (w/app-for-class s0 "ujima-wikipedia")))
  (is (nil? (w/app-for-class s0 "LibreOffice 25.2")) "the LibreOffice splash class is not a match")
  (is (nil? (w/app-for-class s0 nil))))


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


(deftest close-current-returns-to-launcher
  ;; closing the focused app returns home (the launcher window), even with another app still open
  (let [s  (play [{:type :window/new :con-id 1 :class "ujima-launcher"}        ; launcher win-0001
                  {:type :window/new :con-id 2 :class "ujima-wikipedia"}       ; win-0002
                  {:type :window/new :con-id 3 :class "libreoffice-writer"}])  ; win-0003 (current)
        s2 (w/apply-event s {:type :window/close :con-id 3})]
    (is (= "win-0001" (:current s2)) "back to the launcher window, not the other open app")
    (is (= ["win-0001" "win-0002"] (:order s2)) "the other app stays open")))


(deftest launcher-con-detects-eww-death
  ;; the launcher's window::close == eww crashed (the one eww window we track + never close)
  (let [s (play [{:type :window/new :con-id 1 :class "ujima-launcher"}       ; launcher win-0001
                 {:type :window/new :con-id 2 :class "ujima-wikipedia"}])]   ; app win-0002
    (is (true?  (w/launcher-con? s 1))  "the launcher's con")
    (is (false? (w/launcher-con? s 2))  "an app's con")
    (is (false? (w/launcher-con? s 99)) "an unknown con")))


(deftest crash-close-reconciles-like-a-graceful-one
  ;; an app-initiated/crash close arrives as the same :window/close event
  (let [s (play [{:type :window/new   :con-id 1 :class "ujima-wikipedia"}
                 {:type :window/new   :con-id 2 :class "libreoffice-writer"}
                 {:type :window/close :con-id 1}])]
    (is (= ["win-0002"] (:order s)))
    (is (nil? (get-in s [:wm->win 1])))
    (is (= "win-0002" (:current s)) "a background (non-current) close doesn't move focus")))


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


(deftest snapshot-resolves-current-window-for-the-topbar
  (let [snap (w/snapshot (play [{:type :window/new :con-id 1 :class "ujima-wikipedia" :title "Wikipedia"}]))]
    (is (= "win-0001"  (get-in snap [:current-window :id])))
    (is (= "Wikipedia" (get-in snap [:current-window :title]))))
  (is (false? (:show-topbar (:current-window (w/snapshot s0)))) "safe default when no current window"))
