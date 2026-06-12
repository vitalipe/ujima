;; death row: removed in phase 3 (control module)
(ns ujima.target.mock
  (:require [clojure.core.async :as a]

            [ujima.log              :as log]
            [ujima.runtime.protocol :refer [UjimaSystem UjimaDesktop UjimaDiscovery UjimaRuntime]]))
            


(def initial-state {:hostname "ujima-mock"
                    :timezone "UTC"
                    :keyboard-layouts ["us"]
                    :wallpaper nil
                    :volume 50
                    :screen-locked false
                    :control-token {:present? true :type :usb}
                    :settings {}})


(defn deep-merge
  [& maps]
  (apply merge-with
         (fn [a b]
           (if (and (map? a) (map? b))
             (deep-merge a b)
             b))
         maps))


(defrecord MockRuntime [env state* calls*]
  
  UjimaSystem
    (hostname  [_]          (:hostname @state*))
    (hostname! [_ hostname] (swap! state* assoc :hostname hostname))

    (timezone  [_]          (:timezone @state*))
    (timezone! [_ timezone] (swap! state* assoc :timezone timezone))

    (keyboard-layouts  [_]         (:keyboard-layouts @state*))
    (keyboard-layouts! [_ layouts] (swap! state* assoc :keyboard-layouts (into [] layouts)))

    
    (reboot!   [_] (swap! calls* conj {:call :system/reboot   :time (System/currentTimeMillis)}))
    (shutdown! [_] (swap! calls* conj {:call :system/shutdown :time (System/currentTimeMillis)}))


  UjimaDesktop
    (volume  [_]       (:volume @state*))
    (volume! [_ value] (swap! state* assoc :volume value))

    (wallpaper  [_]      (:wallpaper @state*))
    (wallpaper! [_ path] (swap! state* assoc :wallpaper path))

    (screen-locked? [_] (:screen-locked @state*))
    (screen-lock!   [_] (swap! state* assoc :screen-locked true))
    (screen-unlock! [_] (swap! state* assoc :screen-locked false))

    (app-list   [_] [])
    (app-info   [_ name] {}) 
    (app-start! [_ name args])
    (app-kill!  [_ name])
      

  UjimaDiscovery
    (discover-peers!   [_ _opts] (:peers @state*))
    (discover-content! [_ _opts] (:content @state*))

  UjimaRuntime
    (settings  [_]        (:settings @state*))
    (settings! [_ settings] (swap! state* assoc :settings settings))

    (probe-control-token [_] (:control-token @state*))

    (watch-control-token! [_]
      (let [ch* (a/chan (a/sliding-buffer 1))
            interval-ms (get env :control-token-toggle-ms 5000)]
        
        (a/thread
          (loop [present? (get-in @state* [:control-token :present?])]
            (let [token (if present? {:present? true :type :usb} {:present? false})]
              
              (swap! state* assoc :control-token token)
              
              (when (a/>!! ch* token) ;; when chan open?
                (Thread/sleep interval-ms)
                (recur (not present?))))))

        ch*)))


;; REPL and testing helpers
(defn runtime-calls        [{:keys [calls*]}] @calls*)
(defn clear-runtime-calls! [{:keys [calls*]}] (reset! calls* []))


(defn runtime-state        [{:keys [state*]}] @state*)
(defn clear-runtime-state! [{:keys [state*]}] (reset! state* initial-state))


(defn ->runtime [env]
  (let [state (deep-merge initial-state 
                          (get-in env [:state] {}))]

    (log/debug "creating runtime with initial state" state)

    (->MockRuntime env (atom state) 
                       (atom []))))