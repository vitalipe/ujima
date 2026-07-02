(ns ujima.linux.audio
  (:require [cheshire.core  :as json]
            [clojure.string :as str]
            [lib.shell :refer [$!]]))


;; Audio rides the session PipeWire: wpctl (WirePlumber CLI) for get/set, pw-dump for
;; state as JSON. The agent runs inside the desktop session, so XDG_RUNTIME_DIR already
;; points both at the session instance. volume/volume! address a sink — node id (see
;; `sinks`), node name, output-class keyword (:usb | :hdmi — nil no-op when no such
;; sink is present), or wpctl @alias@ — and default to the default sink. mute/mute!
;; are machine-wide (default sink only). switch-output! re-routes app audio to a sink.

(def ^:private default-target "@DEFAULT_AUDIO_SINK@")


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


(defn sources []
  (let [objs (pw-objects)]
    (audio-nodes objs "Audio/Source" (get (default-node-names objs) "default.audio.source"))))


(defn default-sink []
  (first (filter :default? (sinks))))


(defn output-class
  "Coarse transport class of a sink (:usb | :hdmi), from its ALSA node name."
  [{name :name}]
  (when name
    (cond
      (re-find #"(?i)usb"  name) :usb
      (re-find #"(?i)hdmi" name) :hdmi)))


(defn class->sink
  "First sink of `class` (:usb | :hdmi), nil when absent."
  [class]
  (first (filter #(= class (output-class %)) (sinks))))


(defn- resolve-sink
  ;; class keyword -> its sink (nil when absent); sink map -> id; node name -> id;
  ;; ids and @alias@ pass through
  [sink-or-class]
  (let [sink (cond-> sink-or-class (keyword? sink-or-class) class->sink)]
    (cond
      (nil? sink) nil
      (map? sink) (:id sink)

      (and (string? sink) (not (str/starts-with? sink "@")))
      (let [known (sinks)]
        (or (:id (first (filter #(= sink (:name %)) known)))
            (throw (ex-info "no such sink" {:name sink :sinks (mapv :name known)}))))

      :else sink)))


(defn switch-output! [sink]
  ($! wpctl set-default [(resolve-sink sink)])
  (default-sink))


(defn- volume-status [target]
  (let [out ($! wpctl get-volume [target])]
    (if-let [vol (some-> (re-find #"Volume: ([0-9.]+)" out) second parse-double)]
      {:volume (Math/round (* 100 vol))
       :muted? (str/includes? out "[MUTED]")}
      (throw (ex-info "wpctl get-volume: cannot parse" {:target target :out out})))))


(defn volume
  ([] (volume default-target))
  ([sink-or-class] (some-> (resolve-sink sink-or-class) volume-status :volume)))


(defn volume!
  ([value] (volume! default-target value))
  ([sink-or-class value]
   (when-let [target (resolve-sink sink-or-class)]
     (let [value (-> value int (max 0) (min 100))]
       ($! wpctl set-volume [target] [(str value "%")])
       (volume target)))))


(defn mute []
  (:muted? (volume-status default-target)))


(defn mute! [muted?]
  ($! wpctl set-mute [default-target] (if muted? "1" "0"))
  (mute))
