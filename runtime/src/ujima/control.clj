(ns ujima.control
  "Control plane for the appliance: a pure settings machine — scopes, merge,
   persistence — that notifies its converge targets (the OS port, the GUI port;
   passed at init! by ujima.ujimad) after every converge. It knows no port."

  (:require [lib.util    :refer [index-by map-vals map-kv-vals]]
            [babashka.fs :refer [path]]

            [lib.io    :as io]
            [ujima.log :as log]
            [ujima.control.defs     :as defs]
            [ujima.control.registry :refer [->registry
                                            effective-value
                                            default-settings
                                            update-settings-in-scope
                                            scopes]]))


;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------


(defonce ^:private lock*     (Object.))
(defonce ^:private registry* (atom nil))
(defonce ^:private storage*  (atom nil))
(defonce ^:private targets*  (atom []))


(defn- slurp-scope
  "A scope file whose :schema doesn't match defs/schema (incl. pre-schema files) is
   ignored — defaults apply, and the next write replaces it."
  [scope]
  (let [raw (io/slurp-edn (get @storage* scope) {})]
    (if (and (map? raw) (or (empty? raw) (= defs/schema (:schema raw))))
      raw
      (do (log/warn "control: ignoring scope file, schema mismatch"
                    {:scope scope :schema (:schema raw) :expected defs/schema})
          {}))))


(defn- spit-scope! [scope scope-edn]
  (io/spit-file-atomic! (get @storage* scope) (assoc scope-edn :schema defs/schema)))


;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------


(defn- notify-converged! [settings prv]
  (doseq [f @targets*]
    (try (f settings prv)
         (catch Throwable e
           (log/error "control: converge target failed" {:error (ex-message e)})))))


(defn init!
  "`:converge-targets` is the fixed set of ports this machine drives (the OS
   port, the GUI port, …): each is called with (effective, previous-effective)
   after EVERY converge, INSIDE the critical section — strictly ordered, in
   vector order. previous is nil on external converges (boot, udev): assume
   nothing, everything may have changed. The contract that keeps this safe:
   targets are few, fast, one-way (ujima -> world), and NEVER write settings
   (that recurses the converge). A target's failure is logged and never breaks
   the converge itself."
  [{storage :storage tmp :tmp targets :converge-targets}]
  (reset! targets* (vec targets))
  (reset! registry* (->registry {:settings defs/settings
                                 :scopes   defs/scopes}))
  (reset! storage* (->> defs/scopes
                     (index-by :key)
                     (map-vals :persist?)
                     (map-vals {true storage false tmp})
                     (map-kv-vals (fn [k v] (path v (str (name k) ".edn")))))))


(defn settings
  "Return the effective (merged) settings.
   Read-only, not locked -- a read may observe a scope mid-mutation, which is
   fine for advisory reads. Do NOT read-then-write off this value outside the
   lock; use `update-settings!` for read-modify-write."
  []
  (let [all-scopes         (map slurp-scope (scopes @registry*))
        setting->effective (partial effective-value @registry* all-scopes)]
        
    (->> (default-settings @registry*)
      (map-kv-vals (fn [k default] 
                     (if-some [effective (setting->effective k)]
                       effective
                       default))))))


(defn update-settings!
  "The primitive. `swap!`-style: atomically apply `f` to scope `scope`'s current
   map and converge. Under the lock: read scope.

   `f` MUST be pure over the passed map -- it must not read disk, other
   scopes, or the lock, or the atomicity guarantee breaks.

   set:    (update-settings! :device #(assoc % [:system :hostname] \"meru-01\"))
   clear:  (update-settings! :device #(dissoc % [:system :hostname]))
   multi:  (update-settings! :session #(merge % {...}))"
  [scope f]

  (locking lock*

    (let [prv (settings)]

      (->> scope
        (slurp-scope)
        (update-settings-in-scope @registry* scope f)
        (spit-scope! scope))

      (let [effective (settings)]
        (notify-converged! effective prv)
        effective))))


(defn settings!
  "Sugar over `update-settings!` for the common set-one-or-more-keys case.
   (settings! :device [:system :hostname] \"meru-01\") "
  [scope k v & kvs]
  (update-settings! scope #(apply assoc % k v kvs)))
  

(defn converge-fresh!
  "External converge trigger (boot, udev, power events): assume nothing — every
   target gets (effective, nil) and treats the whole world as possibly changed.
   Converge-only: notifies with the CURRENT persisted scopes, writes nothing.
   Same lock and same path as a mutation -- the only difference from
   `update-settings!` is the absence of a scope write."
  []
  (locking lock*
    (let [effective (settings)]
      (notify-converged! effective nil)
      effective)))
  