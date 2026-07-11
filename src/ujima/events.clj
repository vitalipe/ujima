(ns ujima.events
  (:require [clojure.core.async :as async]
            [ujima.log          :as log]

            [ujima.linux.usb     :as usb]
            [ujima.linux.audio   :as audio]
            [ujima.linux.i3      :as i3]
            [ujima.linux.systemd :as systemd]

            [ujima.desktop.app  :as app]
            [ujima.events.token :as token-events]
            [ujima.events.audio :as audio-events]))


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
                   (log/error "agent: event handler failed" {:watcher watcher :error (ex-message e)})))
            (recur))
        (do (log/error "agent: watcher stream ended — dying for a session rebuild" {:watcher watcher})
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

  ;; admin surface follows the control token on usb storage
  (listen! :usb-storage
           (usb/watch-storage!)
           token-events/on-storage-changed!)

  ;; the app plane derives from the i3 tree — window events are its ticks
  (listen! :i3-windows
           (i3/watch-windows!)
           app/handle-event!)

  ;; scope-death rides the SAME pipe (i3/emit!) so it's queue-ordered on the one listener thread:
  ;; the crash/self-quit go-home backstop
  (systemd/watch-scopes! {:interval-ms (:scope-poll-ms cfg 1000) :emit i3/emit!}))
