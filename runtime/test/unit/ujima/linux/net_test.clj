(ns ujima.linux.net-test
  "The summary pick over interface facts; the facts themselves are `ip -j`'s."
  (:require [clojure.test :refer [deftest is]]
            [ujima.linux.net :as net]))


(deftest lan-ip-picks-the-reachable-address
  (is (= "10.0.0.9"
         (net/lan-ip {:eth0  {:up true :ip "10.0.0.7"}
                      :wlan0 {:up true :ip "10.0.0.9" :gateway "10.0.0.1"}}))
      "a gateway marks the routed interface, rank aside")
  (is (= "10.0.0.9"
         (net/lan-ip {:eth0  {:up false :ip nil}
                      :wlan0 {:up true :ip "10.0.0.9"}}))
      "gatewayless: the first up interface with an address")
  (is (= "10.0.0.7"
         (net/lan-ip {:eth0  {:up true :ip "10.0.0.7"}
                      :wlan0 {:up true :ip "10.0.0.9"}}))
      "wired wins a gatewayless tie")
  (is (nil? (net/lan-ip {})) "no interfaces, no address"))
