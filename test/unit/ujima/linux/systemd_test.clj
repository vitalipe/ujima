(ns ujima.linux.systemd-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.linux.systemd :as systemd]))


(deftest id-of-unit-recovers-the-app-id
  (is (= :draw (systemd/id-of-unit "ujima-app-draw-1783689762704.scope")))
  (is (= :onlyoffice (systemd/id-of-unit "ujima-app-onlyoffice-42.scope"))
      "the launch-unique suffix is stripped, the id kept whole")
  (is (= :draw (systemd/id-of-unit
                 "0::/user.slice/user-1001.slice/user@1001.service/app.slice/ujima-app-draw-9.scope"))
      "works on a cgroup path too")
  (is (nil? (systemd/id-of-unit "session-1.scope")) "someone else's scope is not ours")
  (is (nil? (systemd/id-of-unit "")) "empty is nobody's"))
