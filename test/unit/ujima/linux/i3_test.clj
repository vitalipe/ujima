(ns ujima.linux.i3-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [ujima.linux.i3 :as i3]))


(deftest normalize-window-events
  (is (= {:type :window/new :con-id 42 :wm-window 1001 :class "ujima-wikipedia"
          :transient? false :title "Wikipedia"}
         (i3/normalize {:change "new"
                        :container {:id 42 :window 1001 :name "Wikipedia"
                                    :window_properties {:class "ujima-wikipedia"}}})))
  (is (= {:type :window/close :con-id 7} (i3/normalize {:change "close" :container {:id 7}})))
  (is (= {:type :window/title :con-id 7 :class nil :transient? false :title "Essay.odt"}
         (i3/normalize {:change "title" :container {:id 7 :name "Essay.odt"}})))
  (is (= {:type :window/focus :con-id 7} (i3/normalize {:change "focus" :container {:id 7}}))))


(deftest normalize-carries-late-class-and-transient
  ;; LibreOffice sets WM_CLASS after mapping, so the class shows up on a later title event; and the
  ;; Tip-of-the-Day dialog is born transient (transient_for set)
  (let [titled (i3/normalize {:change "title"
                              :container {:id 9 :name "Untitled 1 — LibreOffice Writer"
                                          :window_properties {:class "libreoffice-writer"}}})
        dialog (i3/normalize {:change "new"
                              :container {:id 3 :window 5 :name "Tip of the Day"
                                          :window_properties {:class "libreoffice-writer"
                                                              :transient_for 27262996}}})]
    (is (= "libreoffice-writer" (:class titled)) "class rides on the title event")
    (is (false? (:transient? titled)))
    (is (true? (:transient? dialog)) "transient_for -> :transient? true")))


(deftest normalize-ignores-non-window-changes
  (is (nil? (i3/normalize {:success true})))                                 ; subscribe reply
  (is (nil? (i3/normalize {:change "fullscreen_mode" :container {:id 7}})))  ; permissive fullscreen
  (is (nil? (i3/normalize {:change "move" :container {:id 7}}))))


;; guards the snake_case wire keys (window_properties/class) against the real i3 JSON format
(deftest normalize-from-real-i3-json
  (let [line (str "{\"change\":\"new\",\"container\":{\"id\":94168603163216,\"window\":16777220,"
                  "\"name\":\"Books\",\"window_properties\":{\"class\":\"ujima-books\","
                  "\"instance\":\"ujima-books\"}}}")
        ev   (i3/normalize (json/parse-string line true))]
    (is (= :window/new (:type ev)))
    (is (= 94168603163216 (:con-id ev)))
    (is (= "ujima-books" (:class ev)))
    (is (= "Books" (:title ev)))))


(deftest tree-windows-walks-tiled-and-floating
  (let [tree {:nodes [{:id 1
                       :nodes [{:id 42 :window 1001 :name "Wikipedia"
                                :window_properties {:class "ujima-wikipedia"}}]
                       :floating_nodes [{:id 7 :window 1002 :name "Tip"
                                         :window_properties {:class "libreoffice-writer"
                                                             :transient_for 42}}]}
                      {:id 2 :nodes []}]}]
    (is (= #{42 7} (set (map :id (i3/tree-windows tree)))))))


(deftest baseline-events-replay-the-tree-as-new
  ;; only real X windows count — bare containers/workspaces are skipped
  (let [tree {:nodes [{:id 1 :nodes [{:id 42 :window 1001 :name "W"
                                      :window_properties {:class "ujima-wikipedia"}}
                                     {:id 5 :nodes []}]}]}]
    (is (= [{:type :window/new :con-id 42 :wm-window 1001 :class "ujima-wikipedia"
             :transient? false :title "W"}]
           (i3/baseline-events tree)))))
