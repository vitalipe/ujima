(ns ujima.desktop.i3-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [ujima.desktop.i3 :as i3]))


(deftest normalize-window-events
  (is (= {:type :window/new :con-id 42 :wm-window 1001 :class "ujima-wikipedia" :title "Wikipedia"}
         (i3/normalize {:change "new"
                        :container {:id 42 :window 1001 :name "Wikipedia"
                                    :window_properties {:class "ujima-wikipedia"}}})))
  (is (= {:type :window/close :con-id 7} (i3/normalize {:change "close" :container {:id 7}})))
  (is (= {:type :window/title :con-id 7 :title "Essay.odt"}
         (i3/normalize {:change "title" :container {:id 7 :name "Essay.odt"}})))
  (is (= {:type :window/focus :con-id 7} (i3/normalize {:change "focus" :container {:id 7}}))))


(deftest normalize-ignores-non-window-changes
  (is (nil? (i3/normalize {:success true})))                                ; subscribe reply
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
