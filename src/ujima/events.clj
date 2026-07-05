(ns ujima.events
  (:require [clojure.core.async :as async]
            [ujima.log          :as log]

            [ujima.linux.usb   :as usb]
            [ujima.linux.audio :as audio]
            [ujima.linux.i3    :as i3]

            [ujima.events.token :as token-events]
            [ujima.events.audio :as audio-events]
            [ujima.events.apps  :as apps-events]))


(defn- listen!
  "Drain a watcher channel into its handler, forever; a handler failure is
   logged and never kills the listener."
  [ch handle!]
  (async/thread
    (loop []
      (when-let [event (async/<!! ch)]
        (try (handle! event)
             (catch Throwable e
               (log/error "agent: event handler failed" {:error (ex-message e)})))
        (recur)))))


(defn init!
  "Wire the world's event sources to their policies (bg threads); init! returns —
   the main thread goes on to hold the shell."
  [cfg]
  (log/info "starting event listeners")

  ;; keeps [:audio :active] aligned with plugged hardware
  (listen! (audio/watch-sinks! {:interval-ms (:audio-poll-ms cfg 1000)})
           audio-events/on-sinks-changed!)

  ;; admin surface follows the control token on usb storage
  (listen! (usb/watch-storage!)
           token-events/on-storage-changed!)

  ;; the app plane derives from the i3 tree — window events are its ticks, and the
  ;; stream also echoes back the :recheck/* self-events the app asked i3 for
  (listen! (i3/watch-windows!)
           apps-events/on-event!))
