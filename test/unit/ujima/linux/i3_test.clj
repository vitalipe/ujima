(ns ujima.linux.i3-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [ujima.linux.i3 :as i3]))


(deftest normalize-collapses-window-events-to-ticks
  (is (= {:type :window/close :con-id 7} (i3/normalize {:change "close" :container {:id 7}})))
  (is (= {:type :window/change} (i3/normalize {:change "new"   :container {:id 42}})))
  (is (= {:type :window/change} (i3/normalize {:change "title" :container {:id 7}})))
  (is (= {:type :window/change} (i3/normalize {:change "focus" :container {:id 7}})))
  (is (= {:type :window/change} (i3/normalize {:change "fullscreen_mode" :container {:id 7}}))))


(deftest normalize-surfaces-workspace-focus
  (is (= {:type :workspace/focus} (i3/normalize {:change "focus" :current {:name "write"}})))
  (is (nil? (i3/normalize {:change "init" :current {:name "write"}}))))


(deftest normalize-ignores-the-rest
  (is (nil? (i3/normalize {:success true})))
  (is (nil? (i3/normalize {:change "move" :container {:id 7}}))))


(deftest window-facts-flatten-tiled-and-floating-with-workspace
  (let [tree {:nodes [{:type "workspace" :name "write"
                       :nodes [{:id 9 :window 1001 :name "Essay" :focused true :window_type "normal"
                                :fullscreen_mode 1}]
                       :floating_nodes [{:id 7 :window 1002 :name "Tip" :window_type "dialog"}]}
                      {:type "workspace" :name "1"
                       :nodes [{:id 5 :nodes []}]}]}]
    (is (= [{:con-id 9 :workspace "write" :focused? true  :floating? false :wtype "normal" :fullscreen? true  :title "Essay"}
            {:con-id 7 :workspace "write" :focused? false :floating? true  :wtype "dialog" :fullscreen? false :title "Tip"}]
           (i3/window-facts tree)))))


(deftest window-facts-from-real-i3-json
  (let [line (str "{\"nodes\":[{\"type\":\"workspace\",\"name\":\"web\",\"nodes\":"
                  "[{\"id\":42,\"window\":16777220,\"name\":\"Books\",\"focused\":true,"
                  "\"window_type\":\"normal\"}]}]}")]
    (is (= [{:con-id 42 :workspace "web" :focused? true :floating? false :wtype "normal" :fullscreen? false :title "Books"}]
           (i3/window-facts (json/parse-string line true))))))
