(ns console.circle
  "Who is out there, what each machine is doing, and where a verb becomes a request.

   Discovery is a sweep — at startup, then when a person asks. It only ever adds:
   a machine that stops answering keeps its last tree and reads offline, so a class
   list never thins out mid-lesson. Only a person removes one. Nothing persists."
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
  "Asked of the machine we administer — babashka has no NetworkInterface."
  [key self-addr]
  (let [{:keys [status data]} (api/machine {:key key :addr self-addr})
        ip                    (get-in data [:net :ip])]
    (when (and (= :ok status) ip)
      {:id     (:id data)
       :ip     ip
       :prefix (some (fn [[_ i]] (when (= ip (:ip i)) (:prefix i)))
                     (get-in data [:net :interfaces]))})))

;; it verified, so it holds our key
(defn- adopt! [id addr]
  (swap! roster* update id merge {:addr addr :online true}))

(defn- sweep!
  "Probe the subnet, twice for the silent — a dropped probe is a missing machine."
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
        ;; found is drawable: pull the trees now
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
  "Sweep, or hand back the one already running — never two."
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
  "The panel sends wall time and a zone; the wire takes an instant."
  [{:keys [tz date time]}]
  (-> (LocalDateTime/parse (str date "T" time))
      (.atZone (ZoneId/of tz))
      (.toInstant)
      (.toEpochMilli)))

(defn- ->request
  "Verb -> [command-path body], nil for one we cannot send. Circle writes :activity, over
   the :session the machine's own bar writes"
  [verb args]
  (case verb
    :mute      ["settings/audio/muted"        {:scope "activity" :value true}]
    :unmute    ["settings/audio/muted"        {:scope "activity" :value false}] ;; a clear would leave a machine its own user muted still muted
    :volume    ["audio/volume"                {:scope "activity" :value (:value args)}]
    :open-app  ["app/open"                    {:app (:app args)}]
    ;; "current" is the machine's own app, resolved there — the panel names it, never resolves it
    :focus     ["desktop/focus"               (when (not= "current" (:app args)) {:app (:app args)})]
    :release   ["desktop/release"             nil]
    :close-app ["app/close"                   nil]   ;; closing lets go of the hold on its own

    :open-url  ["app/open-url"                {:url (:url args)}]
    :lock      ["desktop/lock"                nil]
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
  "One verb, one machine, blocking — jobs fans out and holds the deadline. An :ok
   refetches at once, so a panel never waits out a poll to see what it just did."
  [id verb args]
  (if-let [peer (peer-of id)]
    (let [reply (case verb
                  :settings/write (write-settings! peer (:writes args))
                  (if-let [[path body] (try (->request verb args)
                                            (catch Exception _ nil))]
                    (api/command! peer path body)
                    {:status :fail :data {:reason :unknown-verb}}))]
      (when (= :ok (:status reply)) (refresh! id))
      reply)
    {:status :noreply :data {:reason :unknown-machine}}))


;; ── what the panels read ────────────────────────────────────────────────────

(defn self [] @self*)

(defn- tail [id] (subs id (max 0 (- (count id) 4))))

(defn- labelled
  "The machine's name; its id's tail when it has none; both when two share a name.
   Needs the whole roster, so it is not a fact about one peer."
  [peers]
  (let [shared (->> peers
                    (keep #(some-> (get-in % [:system :name]) str/lower-case))
                    frequencies
                    (keep (fn [[name n]] (when (> n 1) name)))
                    set)]
    (mapv (fn [{:keys [id] :as p}]
            (let [host (get-in p [:system :name])]
              (assoc p :label (cond (str/blank? host)                  (tail id)
                                    (shared (str/lower-case host))     (str host " " (tail id))
                                    :otherwise                          host))))
          peers)))

(defn peers
  "Each tree as it last answered, plus what a machine cannot say about itself."
  []
  (->> @roster*
       (keep (fn [[id {:keys [tree addr online]}]]
               (when tree (assoc tree :id id :addr addr :online (boolean online)))))
       (sort-by :id)
       vec
       labelled))

(defn forget!
  "Drop it. It returns if it boots and someone sweeps."
  [id]
  (swap! roster* dissoc id)
  {:removed id})

(defn init!
  "Poll, and sweep once — before any panel exists, so a window opens onto machines."
  [{:keys [key self-addr] :or {self-addr "127.0.0.1"}}]
  (reset! cfg* {:key key :self-addr self-addr})
  (poll-loop!)
  (rescan!))
