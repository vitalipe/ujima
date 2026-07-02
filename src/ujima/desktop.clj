(ns ujima.desktop
  "Brings up the static eww shell and holds it. The daemon runs as OUR foreground child
   (--no-daemonize) with inherited stdio, so everything it prints — including its dying words —
   lands in the journal instead of a discarded capture pipe. init! blocks on the daemon process;
   it returning means eww is gone, and the caller tears the session down for a cold rebuild.
   cfg = {:eww-config <dir>}."
  (:require [lib.shell :as shell]
            [ujima.log :as log]))


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
  "Start the eww daemon (foreground child), open the surfaces, and BLOCK on the daemon for the
   session's life."
  [cfg]
  (let [dir    (or (:eww-config cfg) "/opt/ujima/desktop/eww")
        daemon (shell/with-spawn (inheriting shell/*spawn*)
                 (shell/sh :eww :--config dir "daemon" :--no-daemonize))]
    (log/info "opening shell" {:eww dir})
    (await-daemon! dir)
    (shell/sh! :eww :--config dir "open-many" "topbar" "launcher" "dock")
    (let [{:keys [exit]} @daemon]
      (log/error "eww daemon exited — session over" {:exit exit}))))
