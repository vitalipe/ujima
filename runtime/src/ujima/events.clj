(ns ujima.events
  "The wiring diagram: ujimad builds the planes, init! connects them — ports and
   policies attached, then every world tap started, last."
  (:require [clojure.core.async :as async]
            [ujima.log          :as log]

            [ujima.linux.audio      :as audio]
            [ujima.linux.disk.block :as block]
            [ujima.linux.i3         :as i3]
            [ujima.linux.systemd    :as systemd]
            [ujima.linux.converge   :as linux]

            [ujima.control               :as control]
            [ujima.storage               :as storage]
            [ujima.desktop.app           :as app]
            [ujima.desktop.eww           :as eww]
            [ujima.desktop.http.converge :as shell-http-converge]

            [ujima.events.audio :as audio-events]
            [ujima.events.clock :as clock-events]
            [ujima.events.token :as token-events]))


(defn listen!
  "Pump a watcher channel into its handler on its own thread. A handler failure is
   logged; the channel closing means a watcher died — exit, let systemd rebuild."
  [watcher ch handle!]
  (async/thread
    (loop []
      (if-let [event (async/<!! ch)]
        (do (try (handle! event)
                 (catch Throwable e
                   (log/error "ujimad: event handler failed" {:watcher watcher :error (ex-message e)})))
            (recur))
        (do (log/error "ujimad: watcher stream ended — dying for a session rebuild" {:watcher watcher})
            (System/exit 1))))))


(defn every-ms
  "A timer as a watcher: {:at millis} now and every MS; a slow consumer sees the latest."
  [ms]
  (let [ch (async/chan (async/sliding-buffer 1))]
    (async/thread
      (loop []
        (async/>!! ch {:at (System/currentTimeMillis)})
        (Thread/sleep (long ms))
        (recur)))
    ch))


(defn init!
  "Ports and policies first — a tap's first event may converge — then the taps."
  [{:keys [audio-poll-ms scope-poll-ms clock-save-ms]
    :or   {audio-poll-ms 1000 scope-poll-ms 1000 clock-save-ms 600000}}]

  
  (log/info "starting event listeners")


  ;; settings -> OS, UI
  (control/on-converge! linux/converge!)
  (control/on-converge! shell-http-converge/converge-ui!)

  ;; apps -> UI, bar
  (app/on-converge! shell-http-converge/converge-apps!)
  (app/on-converge! eww/converge!)

  ;; circle token on a stick -> console
  (storage/on-converge! token-events/on-storage!)

  ;; places (the files area + removable) -> the UI stream
  (storage/on-converge! shell-http-converge/converge-places!)

  ;; plugged sinks -> [:audio :active]
  (listen! :audio-sinks
           (audio/watch-sinks! {:interval-ms audio-poll-ms})
           audio-events/on-sinks-changed!)

  ;; removable partitions -> storage
  (listen! :storage
           (block/watch-partitions!)
           storage/handle-event!)

  ;; window events -> apps
  (listen! :i3-windows
           (i3/watch-windows!)
           app/handle-event!)

  ;; scope death -> apps (crash backstop)
  (listen! :scopes
           (systemd/watch-scopes! {:interval-ms scope-poll-ms})
           app/handle-event!)

  ;; witnessed time -> the clock floor
  (listen! :clock
           (every-ms clock-save-ms)
           clock-events/on-tick!))
