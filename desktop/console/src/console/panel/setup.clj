(ns console.panel.setup
  "Setup's slice of the console edge. Machine writes speak the settings
   plane's language: POST /setup/settings carries {:targets :writes} where
   writes are settings-path pairs validated against the whitelist below —
   a new writable setting is one entry here, not a new route. Clock stays a
   named verb (timezone is a setting, wall time is an edge action) and power is a
   true action. Remove/rescan are panel-plane: they touch the roster this console
   holds, never a machine."
  (:require [clojure.string :as str]
            [console.circle :as circle]
            [console.jobs   :as jobs])
  (:import  [java.time Instant LocalDate LocalTime ZoneId ZonedDateTime]
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

(defn- self-only! [ts]
  (when-not (= ts [(circle/self)])
    (malformed! "only this computer — other machines restart from Circle")))


;; ── jobs ───────────────────────────────────────────────────────────────────
(defn settings-job! [body]
  (jobs/act! circle/send! :setup :settings/write (targets body) {:writes (parse-writes body)}))

(defn clock-job! [body]
  (jobs/act! circle/send! :setup :clock/set (targets body) (clock-args body)))

(defn power-job! [verb body]
  (let [ts (targets body)]
    (self-only! ts)
    (jobs/act! circle/send! :setup verb ts {})))


;; ── panel-plane ops ────────────────────────────────────────────────────────
(defn remove! [body]
  (let [id   (:id body)
        peer (first (filter #(= id (:id %)) (circle/peers)))]
    (cond
      (not (string? id))    (malformed! "remove needs a peer :id")
      (nil? peer)           (malformed! "unknown machine")
      (= id (circle/self))   (malformed! "this computer cannot be removed")
      (:online peer)        (malformed! "only machines that are off can be removed")
      :otherwise            (circle/forget! id))))



;; ── the composed view ──────────────────────────────────────────────────────
(def ^:private now-fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm"))

(defn- machine-now
  "The peer's own wall clock: the instant it last reported, read in its own zone.
   Minute resolution, so a poll tick of staleness never shows."
  [{:keys [timezone clock-ms]}]
  (when clock-ms
    (-> (Instant/ofEpochMilli clock-ms)
        (ZonedDateTime/ofInstant (ZoneId/of (or timezone "UTC")))
        (.format now-fmt))))

(defn- wire-peer [peer]
  (update peer :system #(assoc % :now (machine-now %))))

(defn view []
  {:schema 1
   :self   (circle/self)
   :peers  (mapv wire-peer (circle/peers))
   :scan   (circle/scan)})
