(ns ujima.linux.net.mdns
  "What the network announces about itself, over mDNS — avahi's browser as data.

   avahi-daemon rides the raspios base and avahi-utils is pinned by the image; a machine
   publishes its own record from a static service file the runtime stage installs, so
   browsing asks nothing of a peer beyond its avahi being up."
  (:require [clojure.string :as str]
            [lib.shell      :as shell :refer [$?]]))


;; `-t` ends the browse as soon as avahi's cache is exhausted — a steady ~1s over wifi.
;; This is the watchdog for a wedged tool, not the wait.
(def ^:private browse-timeout-ms 5000)


(defn parse-browse
  "`avahi-browse -p` output -> the distinct addresses something can be REACHED at.

   One record per line, semicolon-separated:
     =;iface;proto;name;type;domain;host;ADDRESS;port;txt
   A `+` line is an announcement carrying no address yet; only `=` has resolved.

   Two kinds are dropped, both learned on hardware. IPv6, because avahi answers with
   link-local fe80:: addresses that carry no zone index here and cannot be dialled. And
   loopback, because a browsing machine hears its OWN record on lo — taking 127.0.0.1
   would shadow that machine's real address everywhere it is shown.

   The advertised NAME is deliberately not returned. It belongs to avahi, it wears a `-2`
   suffix as soon as two machines collide, and it is not an identity: what answers at an
   address is for the caller to establish."
  [out]
  (->> (str/split-lines (str out))
       (keep (fn [line]
               (let [[kind _iface proto _name _type _domain _host addr] (str/split line #";")]
                 (when (and (= "=" kind)
                            (= "IPv4" proto)
                            (not (str/blank? addr))
                            (not (str/starts-with? addr "127.")))
                   addr))))
       (distinct)
       (vec)))


(defn browse
  "Addresses announcing SERVICE (e.g. `_ujima._tcp`). Empty on any failure — no
   avahi-browse on the machine, no multicast on the network, nothing answering — so a
   caller falls back on whatever else it knows rather than losing what it had."
  [service]
  (try
    (-> (shell/with-timeout browse-timeout-ms ($? avahi-browse -p -r -t [service]))
        (:out)
        (parse-browse))
    (catch Throwable _ [])))
