(ns ujima.migration.export
  "What this machine has set, as import commands."
  (:require [schema.ujima.settings :as defs]
            [ujima.control :as control]))


(def ^:private persisted-scopes
  (into #{} (comp (filter :persist?) (map :key)) defs/scopes))


(defn export
  "One entry per (scope, setting) a persisted scope holds. control/init! must have run.

   Secrets are included — the wifi psk and circle token decide whether an upgraded slot is
   reachable at all, so this is NOT queries/public-settings. Persisted scopes only, and
   defaults are never exported: what comes back is what somebody chose, so the target's own
   defaults fill in the rest."
  []
  (->> (control/settings)
       (mapcat (fn [[setting {:keys [scopes]}]]
                 (for [[scope value] scopes
                       ;; nil means the scope holds nothing; control cannot tell that from a
                       ;; value explicitly set to nil, and both leave the target on its default
                       :when (and (persisted-scopes scope) (some? value))]
                   {:scope scope :setting setting :value value})))
       (sort-by (juxt :scope :setting))
       (vec)))
