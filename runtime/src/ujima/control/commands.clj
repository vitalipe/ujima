(ns ujima.control.commands
  "User-intent write verbs: map screen-facing intent onto concrete settings. The caller names
   the scope; which scopes a surface offers is that surface's policy. Verbs return a narrow ack
   and throw ex-info {:error kw} — projections live in ujima.control.queries."
  (:require [malli.core      :as m]
            [malli.error     :as me]
            [malli.transform :as mt]
            [schema.ujima.settings :as defs]
            [ujima.control   :as control]))


;; a missing :shape would skip the gate silently
(doseq [{:keys [key shape]} defs/settings]
  (assert shape (str "no :shape for " key)))

(def ^:private specs
  (into {} (map (juxt :key #(select-keys % [:shape :scopes]))) defs/settings))


(defn change-current-volume!
  "Set the ACTIVE output class's volume. Clamps before storing — a stored
   out-of-range value would re-apply on every converge."
  [value scope]
  (when-not (number? value)
    (throw (ex-info "volume must be a number" {:error :request/malformed :value value})))
  (if-let [output (get (control/settings) [:audio :active])]
    (let [v (-> value int (max 0) (min 100))]
      (control/settings! scope [:audio output :volume] v)
      {:volume v})
    (throw (ex-info "no active audio output" {:error :audio/no-output}))))


(defn change-active-output!
  "Select the active output class (nil = none, which only the event layer
   writes). Idempotent: re-asserting the same class still converges."
  [output scope]
  (let [output (cond-> output (string? output) keyword)]
    (when-not (contains? #{:usb :hdmi nil} output)
      (throw (ex-info "unknown output class" {:error :request/malformed :value output})))
    (control/settings! scope [:audio :active] output)
    {:output output}))



(defn change-keyboard-layout!
  "Set a layout code. Only codes in available-layouts are accepted — a stray
   one would fail converge on every pass."
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
  "Set any setting by its path; its def owns the legal scopes and values."
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