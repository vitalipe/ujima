(ns ujima.desktop
  "Brings up the shell and holds it. eww — its TOP BAR + DOCK only now — runs as OUR foreground
   child (--no-daemonize, inherited stdio so its output lands in the journal). The LAUNCHER is a
   separate chromeless WebKitGTK window (assets/desktop/bin/ujima-launcher) that renders the
   launcher home surface served from the widget HTTP API; it is spawned fire-and-forget and the
   app model places it on HOME like the old eww launcher window. init! blocks on the eww daemon;
   it returning means eww is gone and the caller tears the session down for a cold rebuild.
   cfg = {:eww-config <dir> :launcher-bin <path> :launcher-url <uri> :http {:host <ip> :port <n>}}."
  (:require [lib.shell :as shell]
            [ujima.log :as log]
            [ujima.desktop.http :as http]))


(def ^:private ping-tries 40)   ; x 250ms = 10s for the daemon socket to come up


(defn- inheriting
  "Spawn decorator: the child writes straight to our stdout/stderr (the journal), nothing captured."
  [spawn]
  (fn [opts argv] (spawn (merge {:out :inherit :err :inherit} opts) argv)))


(defn- await-daemon!
  "Poll `eww ping` until the daemon socket answers, or give up loudly. Safe to poll: ping does
   NOT auto-start a daemon on connection failure (verified on HW), so it can't race ours."
  [dir]
  (loop [n ping-tries]
    (when-not (:ok? (shell/sh? :eww :--config dir "ping"))
      (if (pos? n)
        (do (Thread/sleep 250) (recur (dec n)))
        (throw (ex-info "eww daemon never answered ping" {:eww dir}))))))


(defn init!
  "Start the eww daemon (top bar + dock), the widget HTTP API, and the webview launcher, then
   BLOCK on the eww daemon for the session's life."
  [cfg]
  (let [dir    (or (:eww-config cfg)   "/opt/ujima/desktop/eww")
        bin    (or (:launcher-bin cfg) "/opt/ujima/desktop/bin/ujima-launcher")
        url    (or (:launcher-url cfg) "http://127.0.0.1:1337/launcher/")
        daemon (shell/with-spawn (inheriting shell/*spawn*)
                 (shell/sh :eww :--config dir "daemon" :--no-daemonize))]

    (log/info "opening shell" {:eww dir :launcher url})
    (await-daemon! dir)
    (http/start! (:http cfg))                                    ; serves /launcher before the webview loads it
    (shell/sh! :eww :--config dir "open-many" "topbar" "dock")   ; NOT launcher — the webview is the launcher

    ;; launcher webview: fire-and-forget child. The app model un-floats/places it on HOME (the old
    ;; eww launcher's slot); i3 tiles it into the mid-section between the top bar and dock.
    (shell/with-spawn (inheriting shell/*spawn*)
      (shell/sh {:extra-env {"UJIMA_SHELL_URL" url}} bin))

    (let [{:keys [exit]} @daemon]
      (log/error "eww daemon exited — session over" {:exit exit}))))
