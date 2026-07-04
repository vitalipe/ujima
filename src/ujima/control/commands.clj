(ns ujima.control.commands
  "User-intent write verbs over the control plane — the one place that maps
   screen-facing intent (\"set THE volume\") onto concrete settings (which
   output class's volume). Verbs are synchronous: control's lock serializes
   writers, and callers arrive at click rate (drag floods are throttled at the
   /ui edge before they get here). Writes land in the :session scope — cleared
   each session; :device persistence arrives with the console. Verbs return a
   narrow ack of what they wrote; projections live in ujima.control.queries
   (the http layer stitches command-then-query for response bodies). Failures
   throw ex-info {:error <kw>} for the HTTP tier to map onto statuses."
  (:require [ujima.control     :as control]
            [ujima.linux.audio :as audio]))


(defn- current-output []
  (audio/output-class (audio/default-sink)))


(defn set-volume!
  "Set the current output class's volume. Clamps BEFORE storing — an out-of-range
   stored value would re-apply on every converge pass (HW caps at 100)."
  [value]
  (when-not (number? value)
    (throw (ex-info "volume must be a number" {:error :request/malformed :value value})))
  (if-let [output (current-output)]
    (let [v (-> value int (max 0) (min 100))]
      (control/settings! :session [:audio output :volume] v)
      {:volume v})
    (throw (ex-info "no classifiable audio output" {:error :audio/no-output}))))


(defn set-mute!
  "Set mute to a concrete desired state (idempotent)."
  [muted]
  (when-not (boolean? muted)
    (throw (ex-info "muted must be a boolean" {:error :request/malformed :value muted})))
  (control/settings! :session [:audio :muted] muted)
  {:muted muted})


(defn set-layout!
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
