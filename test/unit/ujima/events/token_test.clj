(ns ujima.events.token-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [ujima.events.token :as token]))


(deftest on-storage-changed-decides-from-the-event-mounts
  (let [mount (str (fs/create-temp-dir))]
    (is (nil? (token/on-storage-changed! {:mounts #{mount}}))
        "storage without a token -> absent")
    (spit (str (fs/path mount ".ujima-admin-token")) "")
    (is (= (str (fs/path mount ".ujima-admin-token"))
           (token/on-storage-changed! {:mounts #{mount}}))
        "token found on a mounted stick")
    (is (nil? (token/on-storage-changed! {:mounts #{}}))
        "nothing mounted -> absent")))
