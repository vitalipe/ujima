(ns ujima.linux.audio
  (:require [cheshire.core      :as json]
            [clojure.string     :as str]
            [clojure.core.async :as async]
            [ujima.log          :as log]
            [lib.shell :refer [$!]]))


;; Audio rides the session PipeWire: wpctl (WirePlumber CLI) for get/set, pw-dump
;; for state as JSON. The agent runs inside the desktop session, so
;; XDG_RUNTIME_DIR already points both at the session instance. The surface
;; serves the converge flow — `full-topology` (one snapshot in ujima terms),
;; `apply-sink!` (drive one sink toward a desired {:volume :muted}),
;; `switch-output!` (routing) — plus `watch-sinks!`, the pure device-event
;; mechanism (poll-diff over `topology`) that emits class-set changes; consumers
;; own all policy.


(defn- pw-objects []
  (json/parse-string ($! pw-dump) true))


(defn- default-node-names
  ;; wireplumber's "default" metadata: {"default.audio.sink" <node-name>, ...}
  [objs]
  (into {} (for [obj objs
                 :when (= "default" (get-in obj [:props :metadata.name]))
                 {:keys [key value]} (:metadata obj)]
             [key (:name value)])))


(defn- audio-nodes [objs class default-name]
  (vec (for [{:keys [id info]} objs
             :let [props (:props info)]
             :when (= class (:media.class props))]
         {:id          id
          :name        (:node.name props)
          :description (:node.description props)
          :default?    (= (:node.name props) default-name)})))


(defn sinks []
  (let [objs (pw-objects)]
    (audio-nodes objs "Audio/Sink" (get (default-node-names objs) "default.audio.sink"))))


(defn output-class
  "Coarse transport class of a sink (:usb | :hdmi), from its ALSA node name."
  [{name :name}]
  (when name
    (cond
      (re-find #"(?i)usb"  name) :usb
      (re-find #"(?i)hdmi" name) :hdmi)))


(defn- resolve-sink
  ;; sink map -> id; node name -> id; ids and @alias@ pass through
  [sink]
  (cond
    (nil? sink)     nil
    (keyword? sink) (throw (ex-info "sinks are addressed by id/name/map, not class" {:sink sink}))
    (map? sink)     (:id sink)

    (and (string? sink) (not (str/starts-with? sink "@")))
    (let [known (sinks)]
      (or (:id (first (filter #(= sink (:name %)) known)))
          (throw (ex-info "no such sink" {:name sink :sinks (mapv :name known)}))))

    :else sink))


(defn- volume-status [target]
  (let [out ($! wpctl get-volume [target])]
    (if-let [vol (some-> (re-find #"Volume: ([0-9.]+)" out) second parse-double)]
      {:volume (Math/round (* 100 vol))
       :muted  (str/includes? out "[MUTED]")}
      (throw (ex-info "wpctl get-volume: cannot parse" {:target target :out out})))))


(defn topology
  "The cheap present-sinks read (one pw-dump, no per-sink calls) for diffing
   device events: {:names #{<node-name>…} :classes #{:usb :hdmi}}."
  []
  (let [nodes (audio-nodes (pw-objects) "Audio/Sink" nil)]
    {:names   (into #{} (keep :name) nodes)
     :classes (into #{} (keep output-class) nodes)}))


(defn full-topology
  "One snapshot of the audio world in ujima terms:
     {:default <class|nil>                          ; class of the current default sink
      :sinks   {<class> {:id :name :volume :muted}}}
   One pw-dump for topology + one wpctl call per classified sink (it carries both
   volume and mute). First sink of a class wins; unclassified sinks aren't ours."
  []
  (let [objs    (pw-objects)
        default (get (default-node-names objs) "default.audio.sink")
        nodes   (audio-nodes objs "Audio/Sink" default)]
    {:default (some #(when (:default? %) (output-class %)) nodes)
     :sinks   (reduce (fn [m {:keys [id name] :as node}]
                        (let [class (output-class node)]
                          (if (and class (not (contains? m class)))
                            (assoc m class (merge {:id id :name name} (volume-status id)))
                            m)))
                      {} nodes)}))


(defn apply-sink!
  "Drive one sink toward the desired state; absent/nil fields are left alone."
  [sink {:keys [volume muted]}]
  (when-let [target (resolve-sink sink)]
    (when (some? volume)
      ($! wpctl set-volume [target] [(str (-> volume int (max 0) (min 100)) "%")]))
    (when (some? muted)
      ($! wpctl set-mute [target] (if muted "1" "0")))))


(defn switch-output!
  "Make `sink` the default output (wireplumber persists it as the configured
   default, which also disarms its auto-switch toward newcomers)."
  [sink]
  ($! wpctl set-default [(resolve-sink sink)]))


(defn watch-sinks!
  "Watch sink presence (poll-diff over the name set) and emit one event per
   change on the returned channel: {:before #{<class>} :classes #{<class>}}.
   The first observation emits with :before = :classes — a baseline, not
   arrivals. Events fire on ANY name change, including same-class swaps where
   the class sets are equal. Pure mechanism — consumers own all policy. A
   pipewire outage is logged once per transition and retried each tick; a slow
   consumer loses oldest events (each event is a self-contained snapshot)."
  [{:keys [interval-ms] :or {interval-ms 1000}}]
  (let [ch (async/chan (async/sliding-buffer 8))]
    (async/thread
      (loop [prv nil ok? true]
        (let [now (try (topology)
                       (catch Throwable e
                         (when ok?
                           (log/warn "audio watch: topology read failed" {:error (ex-message e)}))
                         nil))]
          (when now
            (cond
              (nil? prv)
              (async/>!! ch {:before (:classes now) :classes (:classes now)})

              (not= (:names now) (:names prv))
              (async/>!! ch {:before (:classes prv) :classes (:classes now)})))
          (Thread/sleep interval-ms)
          (recur (or now prv) (some? now)))))
    ch))
