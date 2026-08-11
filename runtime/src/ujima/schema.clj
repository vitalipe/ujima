(ns ujima.schema
  "The wire-shape gate for raw settings writes — pure enforcement over the
   data plane (src/schema): conform values against each entry's :shape."
  (:require [malli.core      :as m]
            [malli.error     :as me]
            [malli.transform :as mt]
            [schema.ujima.settings :as settings]))


(def ^:private shapes
  (into {} (map (juxt :key :shape)) settings/settings))

;; a setting without a :shape would silently skip the gate — die at load instead
(doseq [{:keys [key shape]} settings/settings]
  (assert shape (str "no :shape for " key)))


(defn conform!
  "decode -> validate: the clean value, or the 400 throw. A path with no
   entry has no setting either — passed through for the addressing 404."
  [path value]
  (if-let [shape (shapes path)]
    (let [v (m/decode shape value mt/string-transformer)]
      (if (m/validate shape v)
        v
        (throw (ex-info (->> (me/humanize (m/explain shape v)) flatten (remove nil?) first)
                        {:error :request/malformed :path path :value value}))))
    value))
