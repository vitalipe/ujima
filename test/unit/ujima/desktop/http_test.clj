(ns ujima.desktop.http-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.http :as http]))


(deftest route-maps-paths-to-actions
  (is (= [:stream]           (http/route :get  "/api/desktop/stream")))
  (is (= [:snapshot]         (http/route :get  "/api/desktop/windows")))
  (is (= [:open "wikipedia"] (http/route :post "/api/desktop/apps/wikipedia/open")))
  (is (= [:focus "win-0001"] (http/route :post "/api/desktop/windows/win-0001/focus")))
  (is (= [:close "win-0001"] (http/route :post "/api/desktop/windows/win-0001/close")))
  (is (= [:close-current]    (http/route :post "/api/desktop/windows/current/close"))
      "current/close matches before the generic window close"))


(deftest route-rejects-unknown
  (is (nil? (http/route :get  "/api/desktop/nope")))
  (is (nil? (http/route :post "/api/other/thing")))
  (is (nil? (http/route :delete "/api/desktop/windows/win-0001/close"))))
