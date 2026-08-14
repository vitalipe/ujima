(ns ujima.control.commands
  "User-intent write verbs over the control plane — the one place that maps
   screen-facing intent (\"change THE volume\") onto concrete settings (which
   output class's volume). Verbs are synchronous: control's lock serializes
   writers, and callers arrive at click rate (drag floods are throttled at the
   /ui edge before they get here). The caller names the scope to write — any
   scope, including the persistent :device; which ones a given surface may
   offer is that surface's policy, not this layer's. Verbs return a
   narrow ack of what they wrote; projections live in ujima.control.queries
   (the http layer stitches command-then-query for response bodies). Failures
   throw ex-info {:error <kw>} for the HTTP tier to map onto statuses."
  (:require [malli.core      :as m]
            [malli.error     :as me]
            [malli.transform :as mt]
            [schema.ujima.settings :as defs]
            [ujima.control   :as control]))


;; a setting without a :shape would silently skip the gate — die at load instead
(doseq [{:keys [key shape]} defs/settings]
  (assert shape (str "no :shape for " key)))

(def ^:private specs
  (into {} (map (juxt :key #(select-keys % [:shape :scopes]))) defs/settings))


(defn change-current-volume!
  "Set the ACTIVE output class's volume ([:audio :active] — the setting is the
   truth for \"current\"). Clamps BEFORE storing — an out-of-range stored value
   would re-apply on every converge pass (HW caps at 100)."
  [value scope]
  (when-not (number? value)
    (throw (ex-info "volume must be a number" {:error :request/malformed :value value})))
  (if-let [output (get (control/settings) [:audio :active])]
    (let [v (-> value int (max 0) (min 100))]
      (control/settings! scope [:audio output :volume] v)
      {:volume v})
    (throw (ex-info "no active audio output" {:error :audio/no-output}))))


(defn change-active-output!
  "Select the active output class (nil = none). ujimad's device policy writes
   this on plug/unplug; over HTTP it is settings/audio/active, whose :shape
   takes no nil — only the event layer discovers that there is no output. Idempotent — re-asserting
   the same class still converges, which is what re-applies state onto a swapped
   device of the same class."
  [output scope]
  (let [output (cond-> output (string? output) keyword)]
    (when-not (contains? #{:usb :hdmi nil} output)
      (throw (ex-info "unknown output class" {:error :request/malformed :value output})))
    (control/settings! scope [:audio :active] output)
    {:output output}))



(defn change-keyboard-layout!
  "Set a concrete layout code. Only codes in available-layouts are accepted —
   a stray code persisted into a scope would fail converge on every pass, so
   it must be rejected loudly here at the edge."
  [code scope]
  (when-not (string? code)
    (throw (ex-info "layout must be a string" {:error :request/malformed :value code})))
  (let [layouts (get (control/settings) [:keyboard :available-layouts])]
    (when-not (some #{code} layouts)
      (throw (ex-info "layout not in available-layouts"
                      {:error :keyboard/unknown-layout :value code :layouts layouts})))
    (control/settings! scope [:keyboard :layout] code)
    {:layout code}))


(defn change-setting!
  "Set any setting by its path. The def owns both halves of what is legal: the
   scopes that may hold it, and the :shape its value must decode to."
  [setting value scope]
  (let [{:keys [shape scopes]} (specs setting)]
    (when-not shape
      (throw (ex-info "not a setting" {:error :settings/unknown :setting setting})))
    (when-not (contains? scopes scope)
      (throw (ex-info (str "this setting takes " (clojure.string/join " or " (map name (sort scopes))))
                      {:error :request/malformed :setting setting :scope scope})))
    (let [v (m/decode shape value mt/string-transformer)]
      (when-not (m/validate shape v)
        (throw (ex-info (->> (me/humanize (m/explain shape v)) flatten (remove nil?) first)
                        {:error :request/malformed :setting setting :value value})))
      (control/settings! scope setting v)
      {:value v})))