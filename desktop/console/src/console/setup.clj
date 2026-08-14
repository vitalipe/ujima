(ns console.setup
  "Setup's slice of the console edge. Machine writes speak the settings
   plane's language: POST /setup/settings carries {:targets :writes} where
   writes are settings-path pairs validated against the whitelist below —
   a new writable setting is one entry here, not a new route. Clock stays a
   named verb (timezone is a setting, wall time is an edge action), checks
   and power are true actions. Remove/rescan are panel-plane: they touch the
   remembered list, never a machine."
  (:require [clojure.string :as str]
            [console.jobs   :as jobs])
  (:import  [java.time Duration Instant LocalDate LocalTime ZoneId ZonedDateTime]
            [java.time.format DateTimeFormatter]))


(defn- malformed! [message]
  (throw (ex-info message {:error :request/malformed})))

(defn- targets [body]
  (let [ts (:targets body)]
    (when (or (not (sequential? ts)) (empty? ts) (not-every? string? ts))
      (malformed! "targets must be a non-empty list of peer ids"))
    (vec ts)))


;; ── the writable-settings whitelist: path -> validation (+ coercion) ───────
(defn- layout-codes? [v]
  (and (sequential? v) (seq v)
       (every? #(and (string? %) (not (str/blank? %))) v)))

(def ^:private write-specs
  {[:system :hostname]
   {:valid? #(and (string? %) (re-matches #"[A-Za-z0-9-]{1,16}" %))
    :why    "hostname must be 1-16 letters, numbers or dashes"}

   [:keyboard :available-layouts]
   {:valid? layout-codes?
    :coerce vec
    :why    "layouts must be a non-empty list of layout codes"}

   [:audio :active]
   {:valid? #{"hdmi" "usb"}
    :coerce keyword
    :why    "audio output must be hdmi or usb"}})

(defn- parse-writes [body]
  (let [pairs (:writes body)]
    (when (or (not (sequential? pairs)) (empty? pairs))
      (malformed! "writes must be a non-empty list of {path value} pairs"))
    (into {}
          (map (fn [{:keys [path value]}]
                 (when-not (and (sequential? path) (every? string? path))
                   (malformed! "each write needs a path of name segments"))
                 (let [path (mapv keyword path)
                       {:keys [valid? coerce why]} (write-specs path)]
                   (cond
                     (nil? valid?)     (malformed! (str "not a writable setting: "
                                                        (str/join "/" (map name path))))
                     (not (valid? value)) (malformed! why)
                     :otherwise        [path ((or coerce identity) value)]))))
          pairs)))


(defn- clock-args [body]
  (let [{:keys [tz date time]} body]
    (when-not (and (string? tz) (string? date) (string? time))
      (malformed! "clock needs :tz :date :time"))
    (try (ZoneId/of tz)       (catch Exception _ (malformed! (str "unknown timezone: " tz))))
    (try (LocalDate/parse date) (catch Exception _ (malformed! (str "bad date: " date))))
    (try (LocalTime/parse time) (catch Exception _ (malformed! (str "bad time: " time))))
    {:tz tz :date date :time time}))

(defn- self-only! [transport ts]
  (let [self ((:self transport))]
    (when-not (= ts [self])
      (malformed! "only this computer — other machines restart from Circle"))))


;; ── jobs ───────────────────────────────────────────────────────────────────
(defn settings-job! [transport body]
  (jobs/act! transport :setup :settings/write (targets body) {:writes (parse-writes body)}))

(defn clock-job! [transport body]
  (jobs/act! transport :setup :clock/set (targets body) (clock-args body)))

(defn checks-job! [transport body]
  (jobs/act! transport :setup :checks/run (targets body) {}))

(defn power-job! [transport verb body]
  (let [ts (targets body)]
    (self-only! transport ts)
    (jobs/act! transport :setup verb ts {})))


;; ── panel-plane ops ────────────────────────────────────────────────────────
(defn remove! [transport body]
  (let [id   (:id body)
        peer (first (filter #(= id (:id %)) ((:peers transport))))]
    (cond
      (not (string? id))         (malformed! "remove needs a peer :id")
      (nil? peer)                (malformed! "unknown machine")
      (= id ((:self transport))) (malformed! "this computer cannot be removed")
      (:online peer)             (malformed! "only machines that are off can be removed")
      :otherwise                 (do ((:remove! transport) id) {:removed id}))))

(defn rescan! [transport]
  ((:rescan! transport)))


;; ── the composed view ──────────────────────────────────────────────────────
(def ^:private now-fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm"))

(defn- machine-now
  "The peer's wall clock: actual now + its drift, in its own timezone. The
   offset is mock bookkeeping — the wire carries only the resulting :now."
  [{:keys [timezone clock-off-min]}]
  (-> (Instant/now)
      (.plus (Duration/ofMinutes (or clock-off-min 0)))
      (ZonedDateTime/ofInstant (ZoneId/of (or timezone "Africa/Dar_es_Salaam")))
      (.format now-fmt)))

(defn- wire-peer [peer]
  (update peer :system #(-> %
                            (assoc :now (machine-now %))
                            (dissoc :clock-off-min))))

(defn view [transport]
  {:schema 1
   :self   ((:self transport))
   :peers  (mapv wire-peer ((:peers transport)))})
