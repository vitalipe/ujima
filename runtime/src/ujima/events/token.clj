(ns ujima.events.token
  "Admin-token policy: the console app follows a circle token on removable storage.
   Decisions only — the projection arrives as a ujima.storage converge target, and storage
   reports markers without interpreting them, so the token's SHAPE is known only here.

   EDGE-triggered on purpose: app/run! switches workspace unconditionally, so a
   level-triggered 'token present -> run!' would yank whoever is working to the console
   every time any partition changes."
  (:require [clojure.string      :as str]
            [ujima.log           :as log]
            [ujima.desktop.app   :as app]
            [ujima.linux.systemd :as systemd]))


(def ^:private console  :console)
(def ^:private token-env "UJIMA_CIRCLE_TOKEN")
(def eject-grace-ms 3000)      ; a yank is not always an eject — let a re-insert cancel it


(defonce ^:private eject-gen* (atom 0))


(defn circle-token
  "The circle key a projection carries, nil when none. A marker that is present but
   unparseable (nil value) or carries no :key is NOT a token — storage reports it either
   way, and deciding what counts is this namespace's job."
  [entries]
  (->> entries
       (mapcat :tokens)
       (filter #(= :circle/secret (:type %)))
       (map (comp :key :value))
       (filter string?)
       (remove str/blank?)
       (first)))


(defn transition
  "Pure: what the console should do, given the token BEFORE and AFTER. A first push has no
   before (prev is nil), so booting with a stick already in reads as an arrival — deliberate:
   the admin left it in, and the alternative is a state no one can reach again without
   physical fiddling."
  [before after]
  (cond
    (and after (not= after before)) :open
    (and before (nil? after))       :close
    :else                           nil))


(defn- open! [token before]
  (swap! eject-gen* inc)                          ; a pending close is now stale
  (app/update-app! console {:env {token-env token} :hidden false})
  (when before
    (log/warn "circle token replaced — a console already running keeps the old one until it closes"))
  ;; app/run! switches workspace BEFORE its own active? gate, so ask here: a stick that
  ;; flaps must not yank whoever is working, once per bad contact
  (if (systemd/active? console)
    (log/info "circle token back before the console closed — leaving it as it is")
    (do (log/info "circle token present — opening the console")
        (app/run! console))))


(defn- close-soon! []
  (let [gen (swap! eject-gen* inc)]
    (log/info "circle token gone — closing the console" {:in-ms eject-grace-ms})
    (future
      (Thread/sleep eject-grace-ms)
      (when (= gen @eject-gen*)                   ; nothing re-armed us in the meantime
        (app/update-app! console {:env nil :hidden true})
        (systemd/stop! console)))))


(defn on-storage!
  "Storage converge target. Returns the transition — the decision, for tests."
  [next prev]
  (let [before (circle-token prev)
        after  (circle-token next)
        move   (transition before after)]
    (case move
      :open  (open! after before)
      :close (close-soon!)
      nil)
    move))
