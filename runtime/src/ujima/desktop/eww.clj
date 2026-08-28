(ns ujima.desktop.eww
  "The eww top bar + dock: the daemon lifecycle (init!!) and the bars as a converge target on
   app's (next prv) edge. Debounced so fullscreen churn (F11 spam, an SDL game's flapping)
   coalesces to the settled state."
  (:require [lib.shell :as shell]
            [ujima.log :as log]))


(def ^:private ping-deadline-ms 10000)               ; wall-clock cap for the daemon socket
(def ^:private eww-dir*    (atom nil))               ; config via init!!
(def ^:private shown?      (atom true))              ; bar visibility
(def ^:private gen*        (atom 0))                 ; supersedes a pending debounced flip
(def ^:private debounce-ms 200)                      ; actuate only after fullscreen settles quiet


;; --no-daemonize is load-bearing on every client call: an eww that cannot reach the daemon
;; otherwise starts its OWN server, which then owns a second pair of bars nothing can close.
(defn- actuate!
  "Flip the bars; true when it landed."
  [show?]
  (:ok? (shell/sh? :eww :--config @eww-dir* :--no-daemonize
                   (if show? "open-many" "close") "topbar" "dock")))


(defn lock-surface!
  "Map or unmap the lock window — the shell's own surface, not an app. It is opened only once
   the lock workspace is focused: i3 places a new window on the CURRENT workspace, and an eww
   window opened elsewhere does not stay where it was put. Closing on release keeps it from
   drifting onto an app's workspace."
  [show?]
  (:ok? (shell/sh? :eww :--config @eww-dir* :--no-daemonize
                   (if show? "open" "close") "lockscreen")))


(defn show-bar?
  "Pure: the current mode decides — the snapshot carries :bars-hidden? (solo hides them;
   multi hides only when the focused window is really fullscreen)."
  [snapshot]
  (not (:bars-hidden? snapshot)))


(defn converge!
  "A converge target (fn [next prv]) — DEBOUNCED: each event arms a flip, and only the last one
   still current after debounce-ms actuates. So F11 spam / an SDL game's flapping coalesces to the
   settled state, and we never actuate mid-flap (never perturbing the app)."
  [next _prv]
  (let [want (show-bar? next)
        g    (swap! gen* inc)]
    (future
      (Thread/sleep debounce-ms)
      (when (and (= g @gen*) (not= want @shown?))
        (if (actuate! want)
          (reset! shown? want)
          (log/error "eww bars did not flip" {:want want}))))))


(defn- bars-open?
  "Both bars, as the daemon on the socket reports them."
  [dir]
  (let [{:keys [ok? out]} (shell/sh? :eww :--config dir :--no-daemonize "active-windows")]
    (and ok?
         (boolean (re-find #"(?m)^topbar:" (str out)))
         (boolean (re-find #"(?m)^dock:"   (str out))))))


(defn- probe-bars!
  "Poll until the daemon reports both bars, or the settle window closes. A cold boot routinely
   needs a beat here — the socket answers before the app thread has painted anything."
  [dir]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (or (bars-open? dir)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 150)
            (recur))))))


(defn- open-bars-or-throw!
  "Open the bars and confirm the daemon reports them — eww's exit code has lied in both
   directions, so the daemon's own answer is the only postcondition worth trusting. The bars
   ARE the desktop: no dock is no launcher, no switching and no clock, so exhausting the tries
   is fatal and the supervisor restarts a session that can still be whole."
  [dir]
  (loop [attempt 1]
    (shell/sh? :eww :--config dir :--no-daemonize "open-many" "topbar" "dock")
    (cond
      (probe-bars! dir) true

      (< attempt 3)      (do (log/warn "eww bars not up yet — retrying" {:attempt attempt})
                             (recur (inc attempt)))

      :fail-3-times      (throw (ex-info "eww never opened the bars" {:eww dir :tries 3})))))


(defn- await-daemon!
  "Poll `eww ping` until the daemon socket answers, or give up loudly. ping is the only eww
   command safe to probe with — every other one starts a server when it cannot connect. It
   proves the socket accepts, not that the app thread can service a command; that remaining
   gap is why the client calls carry --no-daemonize. The cap is wall-clock:
   against a wedged daemon each failed ping itself burns ~1s, so counting tries multiplies the
   intended wait (40 tries ran ~50s on HW)."
  [dir]
  (let [deadline (+ (System/currentTimeMillis) ping-deadline-ms)]
    (loop []
      (when-not (:ok? (shell/sh? :eww :--config dir "ping"))
        (if (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 250) (recur))
          (throw (ex-info "eww daemon never answered ping" {:eww dir})))))))


(defn init!!
  "Start the eww daemon (foreground child), open the bars, then BLOCK on the daemon for the
   session — its return means eww is gone and the caller tears the session down."
  [cfg]
  (let [dir    (:eww-config cfg)
        daemon (shell/with-spawn (shell/inheriting shell/*spawn*)
                 (shell/sh :eww :--config dir "daemon" :--no-daemonize))]
    (log/info "opening eww" {:eww dir})
    (reset! eww-dir* dir)

    (await-daemon! dir)
    (open-bars-or-throw! dir)
    
    ;; we start with bars up
    (reset! shown? true)
    
    (let [{:keys [exit]} @daemon]
      (log/error "eww daemon exited — session over" {:exit exit}))))
