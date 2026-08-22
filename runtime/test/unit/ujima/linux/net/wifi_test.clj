(ns ujima.linux.net.wifi-test
  "The keyfile ujimad writes and reads back; NetworkManager itself is not here."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [ujima.linux.net.wifi :as wifi]))


(deftest keyfile-round-trips-the-network
  (doseq [network [{:ssid "IOT" :psk "1337hax0rIOT"}
                   {:ssid "Open Net" :psk nil}
                   {:ssid "odd: a\\b" :psk "  edge spaces\\and\\\\slashes  "}]]
    (is (= network (wifi/parse-keyfile (wifi/keyfile-text network)))
        (str "round-trips " (pr-str (:ssid network))))))


(deftest keyfile-shape
  (let [open   (wifi/keyfile-text {:ssid "Open" :psk nil})
        secure (wifi/keyfile-text {:ssid "Sec" :psk "passphrase"})]
    (is (not (str/includes? open "[wifi-security]")) "an open network carries no security block")
    (is (str/includes? secure "key-mgmt=wpa-psk")    "a psk makes it WPA-PSK")
    (is (str/includes? secure "autoconnect-retries=0") "NM must retry forever, not give up after 4")
    (is (= (wifi/keyfile-text {:ssid "Sec" :psk "passphrase"}) secure) "stable: same network, same bytes")
    (is (str/includes? secure "id=ujima") "the one profile ujimad owns")))


(deftest parse-tolerates-a-foreign-file
  (is (nil? (wifi/parse-keyfile "[connection]\nid=ujima\ntype=ethernet\n")) "no ssid, no network")
  (is (= {:ssid "X" :psk nil} (wifi/parse-keyfile "[wifi]\n  ssid=X  \n")) "whitespace around lines is noise"))
