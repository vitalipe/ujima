(ns ujima.desktop.http.ui
  "The settings side of the /ui tier: the GUI converge port, wired into control's
   :converge-targets by ujima.ujimad next to the OS port. One authoritative NDJSON
   state stream feeds eww (deflisten) — a snapshot on connect, then one line per
   converge that actually changed the projection — plus the verbs where
   interaction ≠ state (throttled volume moves). The apps stream is the sibling
   ujima.desktop.http.app."
  (:require [lib.http.ndjson :as ndjson]
            [lib.throttle :refer [throttle-leading-trailing]]
            [ujima.log          :as log]
            [ujima.control          :as control]
            [ujima.control.commands :as commands]))


;; --- volume moves (interaction ≠ state) -------------------------------------

;; the throttle delivers f's outcome into per-call promises; nobody derefs them on
;; the fire-and-forget move path, so a bare change-current-volume! would fail
;; silently — warn here (an unplugged sink mid-drag must show up in the journal)
(defonce ^:private change-volume-throttled!
  (throttle-leading-trailing 250
    (fn [value]
      (try (commands/change-current-volume! value :session)
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


(defn- next-of
  "The element after `current`, wrapping; (first xs) when current isn't in xs."
  [xs current]
  (let [i (.indexOf (vec xs) current)]
    (if (neg? i)
      (first xs)
      (nth xs (mod (inc i) (count xs))))))


(defn settings->ui
  "Effective settings -> the UI state blob, pure ([:audio :active] is the truth
   for which output). Presentation derivations belong here — :next is the
   switcher's cycle order, not a domain fact."
  [settings]
  (let [output  (get settings [:audio :active])
        layout  (get settings [:keyboard :layout])
        layouts (get settings [:keyboard :available-layouts])]
    {:audio    {:volume (when output (get settings [:audio output :volume]))
                :muted  (get settings [:audio :muted])
                :output output}
     :keyboard {:layout  layout
                :layouts layouts
                :next    (next-of layouts layout)}}))


(defn keyboard-next
  "GET /ui/keyboard/layout/next: the switcher's next layout as a one-shot read,
   so the keybind needn't tap the state stream. Same cycle order settings->ui
   streams; :next stays a /ui concern (set via POST /api/commands/desktop/:scope/keyboard/layout)."
  []
  (let [s (control/settings)]
    {:next (next-of (get s [:keyboard :available-layouts])
                    (get s [:keyboard :layout]))}))


(defonce ^:private state (ndjson/topic! :ui/state))


(defn converge!
  "The GUI converge port (see control/init! for the target contract). Runs
   INSIDE control's critical section, so the stream can't end on a stale line;
   the topic drops a republish that projects to what subscribers already have."
  [settings _prv]
  (ndjson/publish! state (settings->ui settings)))


(defn stream
  "GET /ui/state: hold the connection open — a fresh snapshot line immediately,
   then one line per real change. eww's deflisten reconnect loop rehydrates from
   the snapshot after a ujimad restart."
  [req]
  (ndjson/subscribe! state req))
