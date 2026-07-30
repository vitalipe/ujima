(ns ujima.control.commands
  "User-intent write verbs over the control plane — the one place that maps
   screen-facing intent (\"change THE volume\") onto concrete settings (which
   output class's volume). Verbs are synchronous: control's lock serializes
   writers, and callers arrive at click rate (drag floods are throttled at the
   /ui edge before they get here). Writes land in the :session scope — cleared
   each session; :device persistence arrives with the console. Verbs return a
   narrow ack of what they wrote; projections live in ujima.control.queries
   (the http layer stitches command-then-query for response bodies). Failures
   throw ex-info {:error <kw>} for the HTTP tier to map onto statuses."
  (:require [ujima.control :as control]))


(defn change-current-volume!
  "Set the ACTIVE output class's volume ([:audio :active] — the setting is the
   truth for \"current\"). Clamps BEFORE storing — an out-of-range stored value
   would re-apply on every converge pass (HW caps at 100)."
  [value]
  (when-not (number? value)
    (throw (ex-info "volume must be a number" {:error :request/malformed :value value})))
  (if-let [output (get (control/settings) [:audio :active])]
    (let [v (-> value int (max 0) (min 100))]
      (control/settings! :session [:audio output :volume] v)
      {:volume v})
    (throw (ex-info "no active audio output" {:error :audio/no-output}))))


(defn change-active-output!
  "Select the active output class (nil = none). Written by ujimad's device
   policy on plug/unplug; the console can set it too. Idempotent — re-asserting
   the same class still converges, which is what re-applies state onto a swapped
   device of the same class."
  [output]
  (let [output (cond-> output (string? output) keyword)]
    (when-not (contains? #{:usb :hdmi nil} output)
      (throw (ex-info "unknown output class" {:error :request/malformed :value output})))
    (control/settings! :session [:audio :active] output)
    {:output output}))


(defn change-mute!
  "Set mute to a concrete desired state (idempotent)."
  [muted]
  (when-not (boolean? muted)
    (throw (ex-info "muted must be a boolean" {:error :request/malformed :value muted})))
  (control/settings! :session [:audio :muted] muted)
  {:muted muted})


(defn change-keyboard-layout!
  "Set a concrete layout code. Only codes in available-layouts are accepted —
   a stray code persisted into a scope would fail converge on every pass, so
   it must be rejected loudly here at the edge."
  [code]
  (when-not (string? code)
    (throw (ex-info "layout must be a string" {:error :request/malformed :value code})))
  (let [layouts (get (control/settings) [:keyboard :available-layouts])]
    (when-not (some #{code} layouts)
      (throw (ex-info "layout not in available-layouts"
                      {:error :keyboard/unknown-layout :value code :layouts layouts})))
    (control/settings! :session [:keyboard :layout] code)
    {:layout code}))
