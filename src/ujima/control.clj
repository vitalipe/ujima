(ns ujima.control
  "Control plane for the appliance: holds effective settings and drives device
   state to match them."

  (:require [lib.util    :refer [index-by map-vals map-kv-vals]]
            [babashka.fs :refer [path]]

            [ujima.fs :as fs]
            [ujima.control.defs      :as defs]
            [ujima.control.reconcile :as reconcile]
            [ujima.control.registry  :refer [->registry
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


(defn- slurp-scope [scope]
  (fs/slurp-edn (get @storage* scope) {}))
  

(defn- spit-scope! [scope scope-edn]
  (fs/spit-file-atomic! (get @storage* scope) scope-edn))


;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------


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

   set:    (update-settings! :device #(assoc % :hostname \"meru-01\"))
   clear:  (update-settings! :device #(dissoc % :hostname))
   multi:  (update-settings! :session #(merge % {...}))"
  [scope f]

  (locking lock*

      (->> scope
        (slurp-scope)
        (update-settings-in-scope @registry* scope f)
        (spit-scope! scope))
      
      (reconcile/reconcile! (settings))))


(defn settings!
  "Sugar over `update-settings!` for the common set-one-or-more-keys case.
   (assoc-scope! :device :hostname \"meru-01\") "
  [scope k v & kvs]
  (update-settings! scope #(apply assoc % k v kvs)))
  

(defn reconcile!
  "External converge trigger (boot, udev, power events).
   Converge-only: re-asserts device state from the CURRENT persisted scopes,
   writes nothing. Same lock and same convergence path as a mutation -- the only
   difference from `update-settings!` is the absence of a scope write."
  []
  (locking lock* 
    (reconcile/reconcile! (settings))))
  