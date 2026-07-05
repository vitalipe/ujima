(ns ujima.desktop.app.proc-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.app.catalog :as catalog]
            [ujima.desktop.app.proc    :as proc]))


(def ^:private cat
  (catalog/->catalog
    {:apps [{:id :wikipedia :label "Wikipedia" :icon "wikipedia"
             :exec ["chromium" "--app=https://wikipedia.com" "--class=ujima-wikipedia"]
             :class "ujima-wikipedia"}
            {:id :write :label "Write" :icon "write"
             :exec ["libreoffice" "--writer"] :class "libreoffice-writer"}]}))


(def ^:private tree
  ;; shaped like a real get_tree: named workspaces, one tiled app, one floating dialog
  {:nodes [{:id 0 :type "output"
            :nodes [{:id 1 :type "workspace" :name "wikipedia"
                     :nodes [{:id 42 :window 1001 :name "Wikipedia" :focused true
                              :window_properties {:class "ujima-wikipedia"}}
                             {:id 5 :nodes []}]
                     :floating_nodes [{:id 7 :window 1002 :name "Tip of the Day"
                                       :window_properties {:class "libreoffice-writer"
                                                           :transient_for 42}}]}
                    {:id 2 :type "workspace" :name "write"
                     :nodes [{:id 9 :window 1003 :name "Essay.odt"
                              :window_properties {:class "libreoffice-writer"}}]}]}]})


(deftest windows-flattens-with-placement-context
  (is (= [{:con-id 42 :class "ujima-wikipedia" :title "Wikipedia" :focused? true
           :workspace "wikipedia" :floating? false :transient? false}
          {:con-id 7 :class "libreoffice-writer" :title "Tip of the Day" :focused? false
           :workspace "wikipedia" :floating? true :transient? true}
          {:con-id 9 :class "libreoffice-writer" :title "Essay.odt" :focused? false
           :workspace "write" :floating? false :transient? false}]
         (proc/windows tree))))


(deftest to-place-plans-floaters-and-strays
  (let [ws (proc/windows tree)]
    (is (= [] (proc/to-place cat ws "1"))
        "everything home: tiled on its app's workspace, the dialog floats in peace"))
  (is (= [{:con-id 42 :workspace "wikipedia"}]
         (proc/to-place cat [{:con-id 42 :class "ujima-wikipedia" :title "W" :focused? false
                              :workspace "ujima-loading" :floating? false :transient? false}]
                        "1"))
      "a staged window moves to its app's workspace")
  (is (= [{:con-id 42 :workspace "wikipedia"}]
         (proc/to-place cat [{:con-id 42 :class "ujima-wikipedia" :title "W" :focused? false
                              :workspace "wikipedia" :floating? true :transient? false}]
                        "1"))
      "a floating app window gets tiled even on the right workspace")
  (is (= [{:con-id 3 :workspace "1"}]
         (proc/to-place cat [{:con-id 3 :class "Eww" :title "Eww - launcher" :focused? false
                              :workspace "wikipedia" :floating? true :transient? false}]
                        "1"))
      "the launcher: eww floats+sticks it over everything — it belongs tiled on HOME")
  (is (= [] (proc/to-place cat [{:con-id 3 :class "Eww" :title "Eww - launcher" :focused? false
                                 :workspace "1" :floating? false :transient? false}
                                {:con-id 8 :class "firefox" :title "x" :focused? false
                                 :workspace "1" :floating? true :transient? false}]
                           "1"))
      "a placed launcher rests; unmanaged windows are not ours to move"))


(deftest app-state-machine
  (is (= :closed  (proc/app-state [] nil)))
  (is (= :new     (proc/app-state [] :new))          "we spawned, i3 hasn't shown it yet")
  (is (= :running (proc/app-state [{:con-id 1}] nil)))
  (is (= :running (proc/app-state [{:con-id 1}] :new)) "presence wins — New resolved by the tree")
  (is (= :closing (proc/app-state [{:con-id 1}] :closing)) "close sent, window still up")
  (is (= :closed  (proc/app-state [] :closing))     "absence wins — Closing resolved by the tree"))


(deftest derive-view-reads-the-tree
  (let [ws   (proc/windows tree)
        view (proc/derive-view cat ws {})]
    (is (= :running (:state (first (:apps view)))))
    (is (= :wikipedia (:current view)) "focus resolves to the owning app")
    (is (= "Wikipedia" (:title (first (:apps view)))))
    (is (= :running (:state (second (:apps view))))
        "the settled LibreOffice class is IN the tree — derivation adopts what events never carried")))


(deftest derive-view-honors-side-intents
  (let [view (proc/derive-view cat [] {:wikipedia {:phase :new :at 0}})]
    (is (= :new (:state (first (:apps view)))) "spawned, awaiting the window"))
  (let [ws   [{:con-id 9 :class "ujima-wikipedia" :title "W" :focused? false}]
        view (proc/derive-view cat ws {:wikipedia {:phase :closing :con 9 :at 0}})]
    (is (= :closing (:state (first (:apps view)))) "close sent, quit-confirm may be holding it")))


(deftest resolve-side-lets-the-tree-answer
  (let [ws [{:con-id 9 :class "ujima-wikipedia" :title "W" :focused? false}]]
    (is (= {} (proc/resolve-side cat ws {:wikipedia {:phase :new :at 0}}))
        "New -> Running: the window arrived")
    (is (= {:wikipedia {:phase :new :at 0}}
           (proc/resolve-side cat [] {:wikipedia {:phase :new :at 0}}))
        "still waiting — the timer owns expiry, not the tree")
    (is (= {} (proc/resolve-side cat [] {:wikipedia {:phase :closing :con 9 :at 0}}))
        "Closing -> Closed: the window went away")
    (is (= {:wikipedia {:phase :closing :con 9 :at 0}}
           (proc/resolve-side cat ws {:wikipedia {:phase :closing :con 9 :at 0}}))
        "close sent but the window persists (TuxPaint's confirm)")
    (is (= {} (proc/resolve-side cat
                                 [{:con-id 10 :class "libreoffice-writer" :title "doc2" :focused? false}]
                                 {:write {:phase :closing :con 9 :at 0}}))
        "the CLOSED window is gone — another window of the app keeps it :running, intent done")))


(deftest snapshot-is-the-wire-shape
  (let [view (proc/derive-view cat (proc/windows tree) {})]
    (is (= {:apps [{:id :wikipedia :label "Wikipedia" :icon "wikipedia"
                    :state :running :title "Wikipedia"}
                   {:id :write :label "Write" :icon "write"
                    :state :running :title "Tip of the Day"}]
            :current :wikipedia
            :current-title "Wikipedia"}
           (proc/snapshot view))))
  (is (= {:apps [] :current nil :current-title nil}
         (proc/snapshot (proc/derive-view cat [] {})))
      ":closed apps stay off the wire"))
