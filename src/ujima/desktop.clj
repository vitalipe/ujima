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
(def ^:private x-tries    60)   ; x 250ms = ~15s for X to accept an authorized connection
(def ^:private default-eww-dir "/opt/ujima/desktop/eww")


(defn bars-control
  "A (fn [show?]) the agent uses to hide the top bar + dock while a fullscreen window is focused
   (false = close, true = re-open). Resolves the eww config dir like init!; non-throwing."
  [cfg]
  (let [dir (or (:eww-config cfg) default-eww-dir)]
    (fn [show?]
      (shell/sh? :eww :--config dir (if show? "open-many" "close") "topbar" "dock"))))


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


(defn await-x!
  "Block until X accepts an AUTHORIZED connection, then return. Guards the cold-boot race where the
   agent's first X call (the initial keyboard converge, then eww's GTK init) beats startx writing
   its auth cookie into ~/.Xauthority — the client then connects with no cookie ('no authorization
   protocol specified'), eww's GTK init dies, and the whole session restarts. Probe = `setxkbmap
   -query`, the one X client guaranteed present (the keyboard converge itself uses it). Caps at
   ~15s then proceeds with a warning, so a never-ready X can't wedge boot."
  []
  (loop [n x-tries]
    (when-not (:ok? (shell/sh? :setxkbmap "-query"))
      (if (pos? n)
        (do (Thread/sleep 250) (recur (dec n)))
        (log/warn "X never accepted an authorized connection — proceeding" {})))))


(defn- keep-launcher!
  "Keep the webview launcher alive on a background thread: spawn it, wait, respawn when it exits.
   Load-bearing at boot — a cold session races WebKit's web-process startup, so the FIRST launch
   can die instantly (works on a warm retry); respawning rides that out, and it also brings the
   launcher back if it ever crashes. The process dies with us on session teardown (JVM exit)."
  [bin url]
  (future
    (loop []
      (let [{:keys [exit]} @(shell/with-spawn (inheriting shell/*spawn*)
                              (shell/sh {:extra-env {"UJIMA_SHELL_URL" url}} bin))]
        (log/warn "webview launcher exited — respawning" {:exit exit})
        (Thread/sleep 2000)
        (recur)))))


(defn init!
  "Start the eww daemon (top bar + dock), the widget HTTP API, and the webview launcher, then
   BLOCK on the eww daemon for the session's life."
  [cfg]
  (let [dir    (or (:eww-config cfg)   default-eww-dir)
        bin    (or (:launcher-bin cfg) "/opt/ujima/desktop/bin/ujima-launcher")
        url    (or (:launcher-url cfg) "http://127.0.0.1:1337/launcher/")
        daemon (shell/with-spawn (inheriting shell/*spawn*)
                 (shell/sh :eww :--config dir "daemon" :--no-daemonize))]

    (log/info "opening shell" {:eww dir :launcher url})
    (await-daemon! dir)
    (http/start! (:http cfg))                                    ; serves /launcher before the webview loads it
    (shell/sh! :eww :--config dir "open-many" "topbar" "dock")   ; NOT launcher — the webview is the launcher

    ;; launcher webview: kept alive on a background thread (respawn rides out the cold-boot WebKit
    ;; race + any later crash). The app model un-floats/places it on HOME (the old eww launcher's
    ;; slot); i3 tiles it into the mid-section between the top bar and dock.
    (keep-launcher! bin url)

    (let [{:keys [exit]} @daemon]
      (log/error "eww daemon exited — session over" {:exit exit}))))
