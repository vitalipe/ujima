(ns ujima.linux.net
  (:require [cheshire.core :as json]
            [lib.shell :refer [$?]]))


;; more interface names when x86 lands
(def ^:private interfaces ["eth0" "wlan0"])   ; allowlist AND rank: wired wins ties


(defn- parsed [{:keys [ok? out]}]
  (when ok? (json/parse-string out true)))


(defn- ipv4-of [addr]
  (let [inet (filter (fn [a] (= "inet" (:family a))) (:addr_info addr))]
    (or (first (filter (fn [a] (= "global" (:scope a))) inet))
        (first inet))))   ; link-scope 169.254: a zeroconf'd machine is still reachable


(defn- gateway-of [routes iface]
  (:gateway (first (filter (fn [r] (and (= "default" (:dst r)) (= iface (:dev r))))
                           routes))))


(defn- facts-of [routes addr]
  (let [v4 (ipv4-of addr)]
    {:up      (= "UP" (:operstate addr))
     :ip      (:local v4)
     :prefix  (:prefixlen v4)
     :mac     (:address addr)
     :gateway (gateway-of routes (:ifname addr))
     :dhcp    (boolean (:dynamic v4))}))   ; the address's own flag — works gatewayless


(defn interface-facts
  "{:eth0 {:up :ip :prefix :mac :gateway :dhcp} :wlan0 …}; absent = no such device."
  []
  (let [routes  (parsed ($? ip -j route))
        by-name (into {} (map (juxt :ifname identity)) (parsed ($? ip -j addr)))]
    (into {} (keep (fn [iface]
                     (when-let [addr (by-name iface)]
                       [(keyword iface) (facts-of routes addr)]))
                   interfaces))))


(defn lan-ip
  "The address peers reach us at: the routed interface's, else the first up one."
  [facts]
  (let [ranked (keep facts (map keyword interfaces))]
    (or (:ip (first (filter :gateway ranked)))
        (:ip (first (filter (fn [f] (and (:up f) (:ip f))) ranked))))))
