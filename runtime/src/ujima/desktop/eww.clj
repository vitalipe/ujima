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


(defn- actuate! [show?]
  (shell/sh? :eww :--config @eww-dir* (if show? "open-many" "close") "topbar" "dock"))


(defn show-bar?
  "Pure: bars shown unless we're in solo, or the focused window is fullscreen."
  [snapshot]
  (and (not= :solo (:mode snapshot))
       (or (nil? (get-in snapshot [:current :id]))
           (not (get-in snapshot [:current :fullscreen])))))


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
        (actuate! want)
        (reset! shown? want)))))


(defn- await-daemon!
  "Poll `eww ping` until the daemon socket answers, or give up loudly. The cap is wall-clock:
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
    (shell/sh! :eww :--config dir "open-many" "topbar" "dock")
    (let [{:keys [exit]} @daemon]
      (log/error "eww daemon exited — session over" {:exit exit}))))
