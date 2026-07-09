(ns ujima.desktop.app.windows-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.app.catalog :as catalog]
            [ujima.desktop.app.windows :as windows]))


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

(def ^:private ws (windows/from-tree tree))


(deftest from-tree-flattens-with-placement-context
  (is (= [{:con-id 42 :class "ujima-wikipedia" :title "Wikipedia" :focused? true :fullscreen? false
           :workspace "wikipedia" :floating? false :transient? false}
          {:con-id 7 :class "libreoffice-writer" :title "Tip of the Day" :focused? false :fullscreen? false
           :workspace "wikipedia" :floating? true :transient? true}
          {:con-id 9 :class "libreoffice-writer" :title "Essay.odt" :focused? false :fullscreen? false
           :workspace "write" :floating? false :transient? false}]
         ws)))


(deftest from-tree-marks-fullscreen-windows
  (let [t {:nodes [{:type "workspace" :name "stellarium"
                    :nodes [{:id 88 :window 2001 :name "Stellarium" :fullscreen_mode 1
                             :window_properties {:class "stellarium"}}]}]}]
    (is (true? (:fullscreen? (first (windows/from-tree t)))))))


(deftest of-class-is-case-insensitive
  (is (= [42] (mapv :con-id (windows/of-class ws "UJIMA-Wikipedia"))))
  (is (= [7 9] (mapv :con-id (windows/of-class ws "libreoffice-writer"))))
  (is (= [] (windows/of-class ws "firefox"))))


(deftest apps-present-names-the-windowed-apps
  (is (= #{:wikipedia :write} (windows/apps-present cat ws)))
  (is (= #{} (windows/apps-present cat []))))


(deftest resolve-intents-lets-the-tree-answer
  (is (= {} (windows/resolve-intents {1234 111} ws))
      "the con is gone — the close is done")
  (is (= {42 111} (windows/resolve-intents {42 111} ws))
      "the con persists — a quit-confirm may be holding it (the echo owns expiry)")
  (is (= {42 111} (windows/resolve-intents {42 111 1234 222} ws))
      "answered and pending intents resolve independently"))


(deftest to-place-plans-floaters-and-strays
  (is (= [] (windows/to-place cat ws "1"))
      "everything home: tiled on its app's workspace, the dialog floats in peace")
  (is (= [{:con-id 42 :workspace "wikipedia"}]
         (windows/to-place cat [{:con-id 42 :class "ujima-wikipedia" :title "W" :focused? false
                                 :workspace "ujima-loading" :floating? false :transient? false}]
                           "1"))
      "a staged window moves to its app's workspace")
  (is (= [{:con-id 42 :workspace "wikipedia"}]
         (windows/to-place cat [{:con-id 42 :class "ujima-wikipedia" :title "W" :focused? false
                                 :workspace "wikipedia" :floating? true :transient? false}]
                           "1"))
      "a floating app window gets tiled even on the right workspace")
  (is (= [{:con-id 3 :workspace "1"}]
         (windows/to-place cat [{:con-id 3 :class "Eww" :title "Eww - launcher" :focused? false
                                 :workspace "wikipedia" :floating? true :transient? false}]
                           "1"))
      "the launcher: eww floats+sticks it over everything — it belongs tiled on HOME")
  (is (= [] (windows/to-place cat [{:con-id 3 :class "Eww" :title "Eww - launcher" :focused? false
                                    :workspace "1" :floating? false :transient? false}
                                   {:con-id 8 :class "firefox" :title "x" :focused? false
                                    :workspace "1" :floating? true :transient? false}]
                              "1"))
      "a placed launcher rests; unmanaged windows are not ours to move"))
