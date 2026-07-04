(ns ujima.desktop.ui
  "The /ui tier: the GUI converge port, wired into control's :converge-targets
   by ujima.core next to the OS port. One authoritative NDJSON state stream
   feeds eww (deflisten) — a snapshot on connect, then one line per converge
   that actually changed the projection — plus the verbs where interaction ≠
   state (throttled volume moves). Future /ui domains (activities, windows)
   get their own streams here."
  (:require [org.httpkit.server :as http]
            [lib.edn            :refer [edn->json]]
            [lib.throttle       :refer [throttle-leading-trailing]]
            [ujima.log          :as log]
            [ujima.control.commands :as commands]
            [ujima.control.queries  :as queries]))


;; --- volume moves (interaction ≠ state) -------------------------------------

;; the throttle delivers f's outcome into per-call promises; nobody derefs them on
;; the fire-and-forget move path, so a bare change-current-volume! would fail
;; silently — warn here (an unplugged sink mid-drag must show up in the journal)
(defonce ^:private change-volume-throttled!
  (throttle-leading-trailing 250
    (fn [value]
      (try (commands/change-current-volume! value)
           (catch Exception e
             (log/warn "volume move dropped" {:value value :error (ex-message e)}))))))


(defn volume-moved!
  "Record a slider position; returns immediately.

   Applies the first value immediately, coalesces intermediate drag values,
   and guarantees that the final dragged value is applied."
  [value]
  (when-not (number? value)
    (throw (ex-info "volume must be a number"
                    {:error :request/malformed
                     :value value})))
  (change-volume-throttled! value)
  nil)


;; --- the state stream --------------------------------------------------------

(defonce ^:private subs* (atom #{}))


(defn- state []
  {:audio    (queries/audio-status)
   :keyboard (queries/keyboard-status)})


(defn- state-line [st]
  (str (edn->json st) "\n"))


(defn converge!
  "The GUI converge port (see control/init! for the target contract): stateless —
   pushes the rebuilt projection when settings actually changed, and always when
   prv is nil (external converge: live HW facts like :output may have moved with
   no settings write). Runs INSIDE control's critical section — strictly ordered
   with converges, so the stream can't end on a stale line."
  [settings prv]
  (when (or (nil? prv) (not= settings prv))
    (let [line (state-line (state))]
      (doseq [ch @subs*]
        (http/send! ch line false)))))


(defn stream
  "GET /ui/state: hold the connection open — a fresh snapshot line immediately,
   then one line per real change. eww's deflisten reconnect loop rehydrates from
   the snapshot after an agent restart."
  [req]
  (http/as-channel req
    {:on-open  (fn [ch]
                 (swap! subs* conj ch)
                 (http/send! ch (state-line (state)) false))
     :on-close (fn [ch _] (swap! subs* disj ch))}))
