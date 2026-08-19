(ns console.fleet
  "The circle as this console sees it: who is out there, what each machine is doing,
   and the one place a panel's verb becomes a request.

   Discovery is a sweep, not a subscription — once at startup, then only when a
   person asks. The roster grows from a sweep and never shrinks under one: a machine
   that stops answering keeps its last tree and reads offline, so a class list does
   not thin out mid-lesson. Only a person removes a machine.

   Nothing here persists. The console runs while the token is in, and the fleet it
   knows dies with it."
  (:require [clojure.string :as str]
            [console.api    :as api]
            [lib.task       :as task])
  (:import  [java.time LocalDateTime ZoneId]))


(def ^:private poll-ms   2000)
(def ^:private in-flight 256)   ;; a /24 in one wave — measured 3s on a classroom AP, 8s at 64


(defonce ^:private cfg*    (atom {}))    ;; :key, :self-addr
(defonce ^:private roster* (atom {}))    ;; machine id -> {:addr :tree :online :seen-ms}
(defonce ^:private self*   (atom nil))   ;; this machine's id
(defonce ^:private scan*   (atom nil))   ;; the one sweep: its task and what it found
(defonce ^:private lock    (Object.))


(defn- peer-of [id]
  (when-let [{:keys [addr]} (@roster* id)]
    {:key (:key @cfg*) :id id :addr addr}))

;; bb futures, never a raw executor — SCI on one stops silently
(defn- in-waves [f xs]
  (mapcat (fn [chunk] (mapv deref (mapv #(future (f %)) chunk)))
          (partition-all in-flight xs)))


(defn- store! [id result]
  (swap! roster* update id
         (fn [known]
           (if (= :ok (:status result))
             (assoc known :tree (:data result) :online true :seen-ms (System/currentTimeMillis))
             (assoc known :online false)))))

(defn- refresh! [id]
  (when-let [peer (peer-of id)]
    (store! id (api/machine peer))))

;; ── the sweep ───────────────────────────────────────────────────────────────

(defn- ip->long [ip]
  (reduce (fn [acc octet] (+ (* acc 256) (parse-long octet))) 0 (str/split ip #"\.")))

(defn- long->ip [n]
  (str/join "." (for [shift [24 16 8 0]] (bit-and (bit-shift-right n shift) 255))))

(defn- hosts
  "Every host address on this network. Never wider than a /24 — a class is one
   subnet, and sweeping a /16 is 65k probes nobody asked for."
  [ip prefix]
  (let [bits (- 32 (max (or prefix 24) 24))
        span (bit-shift-left 1 bits)
        base (bit-and (ip->long ip) (bit-not (dec span)))]
    (map #(long->ip (+ base %)) (range 1 (dec span)))))

(defn- our-network
  "The address and prefix of the machine we run on, asked of that machine — the
   console has no local network syscalls, and this is the subnet a class shares."
  [key self-addr]
  (let [{:keys [status data]} (api/machine {:key key :addr self-addr})
        ip                    (get-in data [:net :ip])]
    (when (and (= :ok status) ip)
      {:id     (:id data)
       :ip     ip
       :prefix (some (fn [[_ i]] (when (= ip (:ip i)) (:prefix i)))
                     (get-in data [:net :interfaces]))})))

(defn- adopt!
  "A machine answered and its answer verified — it holds our key, so it is ours."
  [id addr]
  (swap! roster* update id merge {:addr addr :online true}))

(defn- sweep!
  "Probe the subnet, twice for the addresses that said nothing — on a loaded AP a
   single dropped probe is the difference between a machine and a support call."
  []
  (let [{:keys [key self-addr]} @cfg*
        {:keys [id ip prefix]}  (our-network key self-addr)]
    (when id (reset! self* id))
    (if-not ip
      {:found 0 :foreign 0 :reason :no-network}
      (let [probe   (fn [addr] [addr (api/probe key addr)])
            once    (in-waves probe (hosts ip prefix))
            silent  (for [[addr r] once :when (= :noreply (:status r))] addr)
            answers (concat (remove (fn [[_ r]] (= :noreply (:status r))) once)
                            (in-waves probe silent))
            ours    (for [[addr r] answers :when (= :ok (:status r))] [addr (:id (:data r))])
            foreign (count (filter (fn [[_ r]] (= :auth/bad-response (:reason (:data r)))) answers))]
        (locking lock
          (doseq [[addr id] ours] (adopt! id addr)))
        ;; pull their trees now: a machine found is a machine the ring can draw
        (doall (in-waves refresh! (map second ours)))
        {:found (count ours) :foreign foreign}))))


(defn- sweeping? []
  (when-let [t (:task @scan*)] (not (task/finished? t))))

(defn scan
  "What the current or last sweep is doing — the panels' :scan field."
  []
  (when-let [{:keys [task found foreign]} @scan*]
    {:id      (:id task)
     :running (not (task/finished? task))
     :found   found
     :foreign foreign}))

(defn rescan!
  "Sweep. A request that arrives while one is running joins it and is told so —
   there is never a second sweep."
  []
  (locking lock
    (when-not (sweeping?)
      (let [t (task/->task :discover
                           (fn [_] (let [r (sweep!)]
                                     (swap! scan* merge r)
                                     r)))]
        (reset! scan* {:task t})
        (task/run! t)))
    (scan)))


;; ── the poll ────────────────────────────────────────────────────────────────

(defn- poll-once! []
  (doall (in-waves refresh! (keys @roster*))))

(defn- poll-loop! []
  (future
    (loop []
      (try (poll-once!) (catch Throwable _ nil))
      (Thread/sleep poll-ms)
      (recur))))


;; ── verbs ───────────────────────────────────────────────────────────────────

(defn- epoch-ms
  "The panel sends a wall time and the zone to read it in; the wire takes an instant."
  [{:keys [tz date time]}]
  (-> (LocalDateTime/parse (str date "T" time))
      (.atZone (ZoneId/of tz))
      (.toInstant)
      (.toEpochMilli)))

(defn- ->request
  "A console verb -> [command-path body]. Circle's live verbs write :activity, so
   the machine's own bar (which writes :session) cannot answer back; unmute clears
   that hold rather than pinning false. Nil is a verb this console cannot send."
  [verb args]
  (case verb
    :mute      ["settings/audio/muted"        {:scope "activity" :value true}]
    :unmute    ["clear/activity/audio/muted"  nil]
    :release   ["clear/activity"              nil]
    :volume    ["audio/volume"                {:scope "activity" :value (:value args)}]
    :open-app  ["app/open"                    {:app (:app args)}]
    :close-app ["app/close"                   nil]
    :open-url  ["app/open-url"                {:url (:url args)}]
    :lock      ["desktop/lock"                nil]   ;; not built yet — an honest 404
    :unlock    ["desktop/unlock"              nil]
    :restart   ["system/restart"              nil]
    :poweroff  ["system/poweroff"             nil]
    :clock/set ["system/clock"                {:epoch (epoch-ms args) :timezone (:tz args)}]
    nil))

(defn- fold
  "Several requests, one reply: silence outranks refusal, refusal outranks success."
  [replies]
  (let [status (map :status replies)]
    (cond (some #{:noreply} status) {:status :noreply :data {:reason :transport}}
          (some #{:fail} status)    (first (filter #(= :fail (:status %)) replies))
          :otherwise                {:status :ok})))

(defn- write-settings! [peer writes]
  (fold (for [[path value] writes]
          (api/command! peer (str "settings/" (str/join "/" (map name path)))
                        {:scope "device" :value value}))))

(defn send!
  "One verb against one machine, blocking — console.jobs fans it out and holds the
   deadline. A reply that landed is refetched at once, so the panels do not wait
   out a poll to see what they just did."
  [id verb args]
  (if-let [peer (peer-of id)]
    (let [reply (if (= :settings/write verb)
                  (write-settings! peer (:writes args))
                  (if-let [[path body] (try (->request verb args)
                                            (catch Exception _ nil))]
                    (api/command! peer path body)
                    {:status :fail :data {:reason :unknown-verb}}))]
      (when (= :ok (:status reply)) (refresh! id))
      reply)
    {:status :noreply :data {:reason :unknown-machine}}))


;; ── what the panels read ────────────────────────────────────────────────────

(defn self [] @self*)

(defn peers
  "Every machine we know: its tree as it last answered, plus the two facts a
   machine cannot report about itself."
  []
  (->> @roster*
       (keep (fn [[id {:keys [tree addr online]}]]
               (when tree (assoc tree :id id :addr addr :online (boolean online)))))
       (sort-by :id)
       vec))

(defn forget!
  "Drop a machine from the roster. It returns if it boots and someone sweeps."
  [id]
  (swap! roster* dissoc id)
  {:removed id})

(defn init!
  "Start polling and sweep once — the server probes before any panel exists, so a
   window that opens mid-sweep already has machines to draw."
  [{:keys [key self-addr] :or {self-addr "127.0.0.1"}}]
  (reset! cfg* {:key key :self-addr self-addr})
  (poll-loop!)
  (rescan!))
