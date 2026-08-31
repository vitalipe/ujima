(ns ujima.linux.converge
  "\"Here is ujima state — go apply it to linux.\" Desired-vs-ACTUAL, per domain:
   each block reads its slice of the world and corrects only real differences.
   prv is deliberately ignored — a settings diff can't see world drift (a
   re-plugged sink comes back at its own defaults with no setting changed).
   Domains are isolated: one failing block never stops the others."
  (:require [ujima.log            :as log]
            [lib.shell            :as shell]
            [lib.util             :refer [map-vals]]
            [ujima.linux.system   :as system]
            [ujima.linux.keyboard :as keyboard]
            [ujima.linux.audio    :as audio]
            [ujima.linux.net.wifi :as wifi]))


;; converge runs inside control's critical section — a wedged tool must become a
;; loud domain failure, never a forever-held settings lock
(def ^:private command-timeout-ms 15000)


(defn- converge-audio!
  ;; the active class's sink becomes the default (pinning it also disarms
  ;; wireplumber's auto-switch); every present classified sink is driven to its
  ;; class volume + the machine-wide mute — asserting mute everywhere keeps the
  ;; gaps between converges safe (wireplumber's own unplug-fallback lands on an
  ;; already-converged sink).
  [settings]
  (let [{:keys [default sinks]} (audio/full-topology)
        active (get settings [:audio :active])]
    (when-let [sink (get sinks active)]
      (when (not= active default)
        (log/info "converge: switching output" {:to active})
        (audio/switch-output! (:id sink))))
    (doseq [[class actual] sinks]
      (let [desired {:volume (get settings [:audio class :volume])
                     :muted  (get settings [:audio :muted])}
            diff    (into {} (filter (fn [[k v]] (and (some? v) (not= v (get actual k))))
                                     desired))]
        (when (seq diff)
          (log/info "converge: applying" {:sink class :diff diff})
          (audio/apply-sink! (:id actual) diff))))))


(defn- converge-keyboard! [settings]
  (let [desired (get settings [:keyboard :layout])]
    (when (and desired (not= desired (keyboard/layout)))
      (log/info "converge: applying" {:setting [:keyboard :layout] :value desired})
      (keyboard/layout! desired))))


(defn- converge-system!
  [settings]
  (let [timezone (get settings [:system :timezone])]
    (when (and timezone (not= timezone (system/timezone)))
      (log/info "converge: applying" {:setting [:system :timezone] :value timezone})
      (system/timezone! timezone))))


(defn- converge-wifi!
  ;; :off speaks for the radio only; the link itself is NM's business (autoconnect). The psk
  ;; never reaches a log line.
  [settings]
  (let [on?     (= :peer (get settings [:network :wifi :mode]))
        desired {:ssid (get settings [:network :wifi :essid])
                 :psk  (get settings [:network :wifi :psk])}]
    (when (not= on? (wifi/radio))
      (log/info "converge: applying" {:setting [:network :wifi :mode] :value (if on? :peer :off)})
      (wifi/radio! on?))
    (when (and on? (not= desired (wifi/network)))
      (log/info "converge: applying" {:setting [:network :wifi :essid] :value (:ssid desired)})
      (wifi/join! desired))))


(defn- converge-clock!
  ;; the floor only lifts — a clock already ahead (RTC held, NTP synced) is left alone
  [settings]
  (let [floor (get settings [:system :clock :epoch-floor] 0)]
    (when (< (System/currentTimeMillis) floor)
      (log/info "converge: raising the clock to the floor" {:floor floor})
      (system/clock! floor))))


(defn converge!
  "Drive linux to match `settings` (control's records; the domains see values).
   Settings without a consumer here are simply not linux's business."
  [settings _prv]
  (let [values (map-vals :effective settings)]
    (shell/with-timeout command-timeout-ms
      (doseq [[domain f] [[:audio    converge-audio!]
                          [:keyboard converge-keyboard!]
                          [:system   converge-system!]
                          [:wifi     converge-wifi!]
                          [:clock    converge-clock!]]]
        (try (f values)
             (catch Throwable e
               (log/error "converge: domain failed" {:domain domain :error (ex-message e)}))))))
  settings)
