(ns ujima.events
  (:require [clojure.core.async :as async]
            [ujima.log          :as log]

            [ujima.linux.audio   :as audio]
            [ujima.linux.disk.block :as block]
            [ujima.linux.i3      :as i3]
            [ujima.linux.systemd :as systemd]

            [ujima.desktop.app  :as app]
            [ujima.storage      :as storage]
            [ujima.events.audio :as audio-events]
            [ujima.events.clock :as clock-events]))


(defn- listen!
  "Drain a watcher channel into its handler, forever; a handler failure is
   logged and never kills the listener. The channel CLOSING means a world
   watcher died — a desktop that looks alive but is frozen — so exit loudly
   and let systemd rebuild the session."
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


(defn init!
  "Wire the world's event sources to their policies (bg threads); init! returns —
   the main thread goes on to hold the shell."
  [cfg]
  (log/info "starting event listeners")

  ;; keeps [:audio :active] aligned with plugged hardware
  (listen! :audio-sinks
           (audio/watch-sinks! {:interval-ms (:audio-poll-ms cfg 1000)})
           audio-events/on-sinks-changed!)

  ;; removable partitions -> the storage plane, which pushes to its converge targets
  (listen! :storage
           (block/watch-partitions!)
           storage/handle-event!)

  ;; the app plane derives from the i3 tree — window events are its ticks
  (listen! :i3-windows
           (i3/watch-windows!)
           app/handle-event!)

  ;; scope death: the crash / self-quit backstop
  (systemd/watch-scopes! {:interval-ms (:scope-poll-ms cfg 1000) :emit app/handle-event!})

  ;; the software RTC: witnessed time becomes the next boot's clock floor
  (clock-events/init! cfg))
