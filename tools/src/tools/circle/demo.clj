(ns tools.circle.demo
  "One command for a demo circle: clear what is running, find room on the LAN, put a fake
   fleet on it and point a console at its first machine. Host-side only — nothing here
   ships on an image."
  (:require [clojure.string       :as str]
            [babashka.process     :as p]
            [lib.shell            :refer [$?]]
            [tools.circle.sim     :as sim]
            [tools.circle.console :as console]))


(def ^:private machines 8)
(def ^:private scan-from 200)   ; the high end of a /24, usually clear of the DHCP pool
(def ^:private scan-to   250)
(def ^:private sim-timeout-ms 120000)


(defn- wifi-iface
  "The first wifi device NetworkManager knows — `wifi-p2p` is a different type and does not count."
  []
  (->> (:out ($? nmcli -t -f "DEVICE,TYPE" device status))
       (str/split-lines)
       (map #(str/split % #":"))
       (some (fn [[dev type]] (when (= "wifi" type) dev)))))


(defn- iface->base
  "The first three octets of the /24 an interface sits on, trailing dot included."
  [iface]
  (let [out              (str (:out ($? ip "-4" "-br" addr show [iface])))
        [_ a b c prefix] (re-find #"(\d+)\.(\d+)\.(\d+)\.\d+/(\d+)" out)]
    (when-not a
      (throw (ex-info (str iface " carries no IPv4 address") {:iface iface})))
    (when (= "127" a)
      (throw (ex-info (str iface " is loopback — the demo needs an interface on a LAN")
                      {:iface iface})))
    (when (> (parse-long prefix) 24)
      (throw (ex-info (str iface " is a /" prefix " — the demo lays out a /24 window")
                      {:iface iface :prefix prefix})))
    (str a "." b "." c ".")))


(defn- free-run
  "The first run of N consecutive addresses in the scan window that nothing answers for.
   One parallel pass: an arping costs its full timeout when the address IS free, which is
   every address we are hoping to find."
  [iface base n]
  (let [mine (sim/local)
        ips  (mapv #(str base %) (range scan-from (inc scan-to)))
        free (->> ips
                  (mapv (fn [ip]
                          [ip (future (and (not (mine ip))
                                           (not (sim/answers? iface ip))))]))
                  (reduce (fn [m [ip f]] (assoc m ip @f)) {}))]
    (->> (partition n 1 ips)
         (some (fn [run] (when (every? free run) (vec run)))))))


(defn- await-sim!
  "The sim claims one address at a time and records each as it lands. It prints its own
   reason for giving up, so a dead child ends the wait rather than running out the clock."
  [proc n]
  (loop [waited 0]
    (cond
      (= n (count (sim/claimed))) :up
      (not (.isAlive proc))       (throw (ex-info "the sim exited before it claimed its addresses"
                                                  {:wanted n}))
      (>= waited sim-timeout-ms)  (throw (ex-info "the sim did not claim its addresses in time"
                                                  {:claimed (count (sim/claimed)) :wanted n}))
      :else                       (do (Thread/sleep 200) (recur (+ waited 200))))))


(defn up!
  [{:keys [iface token]}]
  (let [iface (or iface (wifi-iface))]
    (when-not iface
      (throw (ex-info "no wifi interface found — name one: bb circle demo <iface>" {})))

    ;; resolve the interface before tearing anything down: a bad name must not cost
    ;; a running sim
    (let [base (iface->base iface)
          _    (sim/down! nil)
          _    (console/down! nil)
          _    (println (str "scanning " base scan-from "-" scan-to " on " iface " for "
                             machines " free addresses..."))
          run  (or (free-run iface base machines)
                   (throw (ex-info (str "no run of " machines " free addresses in "
                                        base scan-from "-" scan-to " on " iface)
                                   {:iface iface})))
          span (str (first run) "-" (last (str/split (last run) #"\.")))]

      ;; the console runs in the foreground, so the sim is a child and leaving takes it too
      (.addShutdownHook (Runtime/getRuntime) (Thread. #(sim/down! nil)))
      (-> (apply p/process {:dir (System/getProperty "user.dir") :out :inherit :err :inherit}
                 (cond-> ["bb" "circle" "sim" "up" "--range" span]
                   token (conj "--token" token)))
          (:proc)
          (await-sim! machines))

      (println (str "demo: " machines " machines on " span ", console on " (first run)))
      (console/up! {:self (first run) :token token}))))
