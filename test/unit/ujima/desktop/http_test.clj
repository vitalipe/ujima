(ns ujima.desktop.http-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.http :as http]))


(deftest route-maps-both-tiers
  (is (= :audio/status        (http/route :get  "/api/audio")))
  (is (= :keyboard/status     (http/route :get  "/api/input/keyboard")))
  (is (= :audio/set-volume    (http/route :post "/api/audio/volume")))
  (is (= :audio/set-mute      (http/route :post "/api/audio/mute")))
  (is (= :shell/volume-move   (http/route :post "/shell/volume/move")))
  (is (= :shell/mute-toggle   (http/route :post "/shell/mute/toggle")))
  (is (= :shell/keyboard-next (http/route :post "/shell/keyboard/next"))))


(deftest route-tolerates-trailing-slashes
  (is (= :audio/set-mute (http/route :post "/api/audio/mute/")))
  (is (= :audio/status   (http/route :get  "/api/audio/"))))


(deftest route-rejects-unrouted
  (is (nil? (http/route :get  "/api/nope")))
  (is (nil? (http/route :post "/api/audio"))          "method matters")
  (is (nil? (http/route :get  "/shell/keyboard/next")) "commands are POST-only")
  (is (nil? (http/route :get  "/"))))
