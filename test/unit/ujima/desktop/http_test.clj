(ns ujima.desktop.http-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.http :as http]))


(deftest route-maps-both-tiers
  (is (= :audio/status        (http/route :get  "/api/audio")))
  (is (= :keyboard/status     (http/route :get  "/api/input/keyboard")))
  (is (= :audio/set-volume    (http/route :post "/api/audio/volume")))
  (is (= :audio/set-mute      (http/route :post "/api/audio/mute")))
  (is (= :keyboard/set-layout (http/route :post "/api/input/keyboard/layout")))
  (is (= :ui/state            (http/route :get  "/ui/state")))
  (is (= :ui/volume-move      (http/route :post "/ui/volume/move"))))


(deftest route-tolerates-trailing-slashes
  (is (= :audio/set-mute (http/route :post "/api/audio/mute/")))
  (is (= :audio/status   (http/route :get  "/api/audio/"))))


(deftest route-rejects-unrouted
  (is (nil? (http/route :get  "/api/nope")))
  (is (nil? (http/route :post "/api/audio"))              "method matters")
  (is (nil? (http/route :get  "/api/input/keyboard/layout")) "writes are POST-only")
  (is (nil? (http/route :post "/shell/volume/move"))      "the /shell tier is gone — it's /ui now")
  (is (nil? (http/route :get  "/"))))
