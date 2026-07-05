(ns ujima.desktop.http-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.http :as http]))


(deftest route-maps-both-tiers
  (is (= :audio/status     (http/route :get  "/api/audio")))
  (is (= :keyboard/status  (http/route :get  "/api/input/keyboard")))
  (is (= :audio/volume     (http/route :post "/api/audio/volume")))
  (is (= :audio/mute       (http/route :post "/api/audio/mute")))
  (is (= :audio/output     (http/route :post "/api/audio/output")))
  (is (= :keyboard/layout  (http/route :post "/api/input/keyboard/layout")))
  (is (= :ui/state         (http/route :get  "/ui/state")))
  (is (= :ui/apps          (http/route :get  "/ui/apps")))
  (is (= :ui/keyboard-next (http/route :get  "/ui/keyboard/layout/next")))
  (is (= :ui/volume        (http/route :post "/ui/volume/move")))
  (is (= :app/catalog      (http/route :get  "/app/catalog")))
  (is (= :app/run          (http/route :post "/app/run")))
  (is (= :app/close        (http/route :post "/app/close"))))


(deftest route-tolerates-trailing-slashes
  (is (= :audio/mute   (http/route :post "/api/audio/mute/")))
  (is (= :audio/status (http/route :get  "/api/audio/"))))


(deftest route-rejects-unrouted
  (is (nil? (http/route :get  "/api/nope")))
  (is (nil? (http/route :post "/app/catalog"))      "the catalog is read-only")
  (is (nil? (http/route :get  "/app/run"))          "run is POST-only")
  (is (nil? (http/route :post "/api/audio"))              "method matters")
  (is (nil? (http/route :get  "/api/input/keyboard/layout")) "writes are POST-only")
  (is (nil? (http/route :post "/shell/volume/move"))      "the /shell tier is gone — it's /ui now")
  (is (nil? (http/route :get  "/"))))
