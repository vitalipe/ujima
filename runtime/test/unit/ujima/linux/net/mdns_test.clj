(ns ujima.linux.net.mdns-test
  "The records avahi answers with; avahi itself is not here."
  (:require [clojure.test   :refer [deftest is]]
            [clojure.string :as str]
            [ujima.linux.net.mdns :as mdns]))


;; verbatim `avahi-browse -p -r -t _ujima._tcp`, run on a Pi with two machines up —
;; both of them announcing, and this one hearing itself on lo as well as on wlan0
(def ^:private captured
  (str "+;wlan0;IPv6;ujimaos-2;_ujima._tcp;local\n"
       "+;wlan0;IPv6;ujima-3cc4;_ujima._tcp;local\n"
       "+;wlan0;IPv4;ujimaos-2;_ujima._tcp;local\n"
       "+;wlan0;IPv4;ujima-3cc4;_ujima._tcp;local\n"
       "+;lo;IPv4;ujima-3cc4;_ujima._tcp;local\n"
       "=;wlan0;IPv6;ujimaos-2;_ujima._tcp;local;ujimaos-2.local;fe80::2ecf:67ff:fec1:3b7d;1337;\n"
       "=;wlan0;IPv4;ujimaos-2;_ujima._tcp;local;ujimaos-2.local;192.168.1.195;1337;\n"
       "=;wlan0;IPv6;ujima-3cc4;_ujima._tcp;local;ujima-3cc4.local;fe80::2ecf:67ff:fe61:950b;1337;\n"
       "=;wlan0;IPv4;ujima-3cc4;_ujima._tcp;local;ujima-3cc4.local;192.168.1.177;1337;\n"
       "=;lo;IPv4;ujima-3cc4;_ujima._tcp;local;ujima-3cc4.local;127.0.0.1;1337;"))


(deftest reads-the-addresses-something-can-be-reached-at
  (is (= ["192.168.1.195" "192.168.1.177"] (mdns/parse-browse captured))
      "two machines announced, and these are the addresses to dial them on"))


(deftest what-is-announced-but-is-not-reach
  (let [addrs (mdns/parse-browse captured)]
    (is (not-any? #(str/starts-with? % "127.") addrs)
        "a browsing machine hears its own record on lo — 127.0.0.1 would shadow its real address")
    (is (not-any? #(str/includes? % ":") addrs)
        "avahi answers IPv6 as zone-less link-local fe80::, which cannot be dialled from here")))


(deftest an-announcement-is-not-an-answer
  (is (= [] (mdns/parse-browse "+;wlan0;IPv4;ujima-3cc4;_ujima._tcp;local"))
      "a + line names a service that has not resolved to an address yet"))


(deftest one-address-however-often-it-is-said
  (is (= ["10.0.0.5"]
         (mdns/parse-browse (str "=;eth0;IPv4;a;_ujima._tcp;local;a.local;10.0.0.5;1337;\n"
                                 "=;wlan0;IPv4;a;_ujima._tcp;local;a.local;10.0.0.5;1337;")))
      "the same address heard on two interfaces is still one address"))


(deftest nothing-in-nothing-out
  (is (= [] (mdns/parse-browse "")) "a browse that found nothing")
  (is (= [] (mdns/parse-browse nil)) "no avahi-browse on the machine, so no output at all")
  (is (= [] (mdns/parse-browse "not;a;record\n\n")) "foreign lines are noise, not a crash"))
