(ns ujima.desktop.eww
  "The eww top bar + dock: the daemon lifecycle (init!!) and the bars as a converge target on
   app's (next prv) edge. show-bar? is a pure fold — the latch, as data."
  (:require [lib.shell :as shell]
            [ujima.log :as log]))


(def ^:private ping-tries 40)                        ; x 250ms = 10s for the daemon socket
(def ^:private eww-dir*   (atom "/opt/ujima/desktop/eww"))
(def ^:private shown?     (atom true))               ; bar visibility — the fold's only memory


(defn- actuate! [show?]
  (shell/sh? :eww :--config @eww-dir* (if show? "open-many" "close") "topbar" "dock"))


(defn show-bar?
  "Pure: should the bars be shown, given next + prv snapshots and last tick's decision. Hide for
   a fullscreen focused app; stay hidden through that app's own fullscreen flapping."
  [next prv prev-shown?]
  (let [cur (get-in next [:current :id])
        was (get-in prv  [:current :id])]
    (cond
      (nil? cur)                            true    ; launcher / unmanaged -> show
      (get-in next [:current :fullscreen])  false   ; fullscreen -> hide
      (and (= cur was) (not prev-shown?))   false   ; same app, were hidden -> stay hidden
      :else                                 true)))


(defn converge!
  "A converge target (fn [next prv]): actuate the bars only when their visibility flips."
  [next prv]
  (let [now (show-bar? next prv @shown?)]
    (when (not= now @shown?)
      (actuate! now)
      (reset! shown? now))))


(defn- await-daemon!
  "Poll `eww ping` until the daemon socket answers, or give up loudly."
  [dir]
  (loop [n ping-tries]
    (when-not (:ok? (shell/sh? :eww :--config dir "ping"))
      (if (pos? n)
        (do (Thread/sleep 250) (recur (dec n)))
        (throw (ex-info "eww daemon never answered ping" {:eww dir}))))))


(defn init!!
  "Start the eww daemon (foreground child), open the bars, then BLOCK on the daemon for the
   session — its return means eww is gone and the caller tears the session down."
  [cfg]
  (let [dir    (or (:eww-config cfg) "/opt/ujima/desktop/eww")
        daemon (shell/with-spawn (shell/inheriting shell/*spawn*)
                 (shell/sh :eww :--config dir "daemon" :--no-daemonize))]
    (log/info "opening eww" {:eww dir})
    (reset! eww-dir* dir)
    (await-daemon! dir)
    (shell/sh! :eww :--config dir "open-many" "topbar" "dock")
    (let [{:keys [exit]} @daemon]
      (log/error "eww daemon exited — session over" {:exit exit}))))
