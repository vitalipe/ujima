(ns ujima.control
  "Control plane for the appliance: holds effective settings and drives device
   state to match them."

  (:require [lib.util    :refer [index-by map-vals map-kv-vals]]
            [babashka.fs :refer [path]]

            [lib.io    :as io]
            [ujima.log :as log]
            [ujima.control.defs      :as defs]
            [ujima.control.reconcile :as reconcile]
            [ujima.control.registry  :refer [->registry
                                             effective-value
                                             default-settings
                                             reconcilable-settings
                                             update-settings-in-scope
                                             scopes]]))


;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------


(defonce ^:private lock*      (Object.))
(defonce ^:private registry*  (atom nil))
(defonce ^:private storage*   (atom nil))
(defonce ^:private listeners* (atom []))


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


(defn on-converge!
  "Register `f` as a converge target next to the OS: it runs after EVERY converge
   (each write and each external reconcile!), INSIDE the critical section — so
   notifications are strictly ordered with converges. The contract that keeps that
   safe: listeners are few, registered at boot, fast, one-way (ujima -> world) —
   a listener must NEVER write settings (that recurses the converge). Failures are
   logged and never break the converge itself."
  [f]
  (swap! listeners* conj f))


(defn- notify-converged! [settings]
  (doseq [f @listeners*]
    (try (f settings)
         (catch Throwable e
           (log/error "control: converge listener failed" {:error (ex-message e)})))))


(defn init! [{storage :storage tmp :tmp}]
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

      (->> scope
        (slurp-scope)
        (update-settings-in-scope @registry* scope f)
        (spit-scope! scope))

      (let [effective (settings)]
        (reconcile/reconcile! (reconcilable-settings @registry* effective))
        (notify-converged! effective)
        effective)))


(defn settings!
  "Sugar over `update-settings!` for the common set-one-or-more-keys case.
   (settings! :device [:system :hostname] \"meru-01\") "
  [scope k v & kvs]
  (update-settings! scope #(apply assoc % k v kvs)))
  

(defn reconcile!
  "External converge trigger (boot, udev, power events).
   Converge-only: re-asserts device state from the CURRENT persisted scopes,
   writes nothing. Same lock and same convergence path as a mutation -- the only
   difference from `update-settings!` is the absence of a scope write."
  []
  (locking lock*
    (let [effective (settings)]
      (reconcile/reconcile! (reconcilable-settings @registry* effective))
      (notify-converged! effective)
      effective)))
  