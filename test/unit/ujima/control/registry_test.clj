(ns ujima.control.registry-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.control.registry :as registry]
            [ujima.control.defs :as defs]))


;; Self-contained fixture (deliberately independent of defs.clj). Keys are path vectors,
;; the system's key shape. Includes a falsy default ([:a :muted] false) and a multi-scope
;; setting ([:a :all]) to cover the regressions this logic hit during development.
(def schema
  {:scopes   [{:key :device :persist? true}
              {:key :session}
              {:key :activity}]
   :settings [{:key [:a :one]   :default "d1"  :scopes #{:device}}
              {:key [:a :muted] :default false :scopes #{:session :activity}}
              {:key [:a :all]   :default 3     :scopes #{:device :session :activity}}]})

(def reg (registry/->registry schema))


;; ---------------------------------------------------------------------------
;; ->registry derivation
;; ---------------------------------------------------------------------------

(deftest settings-of-scope-maps-each-scope-to-its-allowed-settings
  ;; guards the "every scope -> #{}" regression
  (is (= {:device   #{[:a :one] [:a :all]}
          :session  #{[:a :muted] [:a :all]}
          :activity #{[:a :muted] [:a :all]}}
         (:settings-of-scope reg))))

(deftest registry-indexes-and-passes-through
  (is (= #{[:a :one] [:a :muted] [:a :all]} (set (keys (:settings-by-key reg)))))
  (is (= #{:device :session :activity} (set (keys (:scope-by-key reg)))))
  (is (= (:scopes schema)   (:scopes reg)))
  (is (= (:settings schema) (:settings reg))))


;; ---------------------------------------------------------------------------
;; default-settings
;; ---------------------------------------------------------------------------

(deftest default-settings-includes-all-defaults-even-falsy
  (let [defaults (registry/default-settings reg)]
    (is (= {[:a :one] "d1" [:a :muted] false [:a :all] 3} defaults))
    (is (contains? defaults [:a :muted]))
    (is (false? (get defaults [:a :muted])))))


;; ---------------------------------------------------------------------------
;; scope->allowed-settings / scopes
;; ---------------------------------------------------------------------------

(deftest scope->allowed-settings-matches-derivation
  (is (= #{[:a :one] [:a :all]}   (registry/scope->allowed-settings reg :device)))
  (is (= #{[:a :muted] [:a :all]} (registry/scope->allowed-settings reg :session)))
  (is (nil? (registry/scope->allowed-settings reg :nope))))

(deftest scopes-returns-keys-in-declaration-order
  (is (= [:device :session :activity] (registry/scopes reg))))


;; ---------------------------------------------------------------------------
;; effective-value (override merge; first arg ignored)
;; ---------------------------------------------------------------------------

(deftest effective-value-last-non-nil-wins
  (is (= "z" (registry/effective-value nil
                                       [{:settings {[:a :k] "a"}} {:settings {[:a :k] "z"}}]
                                       [:a :k]))))

(deftest effective-value-skips-scopes-that-do-not-set-the-key
  (is (= "a" (registry/effective-value nil
                                       [{:settings {[:a :k] "a"}}
                                        {:settings {}}
                                        {:settings {[:a :other] 1}}]
                                       [:a :k]))))

(deftest effective-value-falsy-value-still-overrides
  ;; guards the if-some/falsy regression
  (is (false? (registry/effective-value nil
                                        [{:settings {[:a :k] true}} {:settings {[:a :k] false}}]
                                        [:a :k])))
  (is (zero? (registry/effective-value nil
                                       [{:settings {[:a :k] 5}} {:settings {[:a :k] 0}}]
                                       [:a :k]))))

(deftest effective-value-nil-when-no-scope-sets-it
  (is (nil? (registry/effective-value nil [{:settings {}} {:settings {[:a :other] 1}}] [:a :k])))
  (is (nil? (registry/effective-value nil [] [:a :k]))))

(deftest effective-value-sibling-paths-override-independently
  ;; the point of path keys: a scope overriding [:audio :usb :volume] must not
  ;; disturb [:audio :hdmi :volume] (each path is its own scalar setting)
  (let [scopes [{:settings {[:audio :usb :volume] 40 [:audio :hdmi :volume] 70}}
                {:settings {[:audio :usb :volume] 20}}]]
    (is (= 20 (registry/effective-value nil scopes [:audio :usb :volume])))
    (is (= 70 (registry/effective-value nil scopes [:audio :hdmi :volume])))))


;; ---------------------------------------------------------------------------
;; update-settings-in-scope (write path: apply f then whitelist)
;; ---------------------------------------------------------------------------

(deftest update-applies-f-then-drops-keys-not-allowed-in-scope
  ;; [:a :muted] is not allowed in :device -> dropped; allowed keys survive
  (is (= {:settings {[:a :one] "x" [:a :all] 9}}
         (registry/update-settings-in-scope
           reg :device
           #(assoc % [:a :one] "x" [:a :all] 9 [:a :muted] true)
           {:settings {}}))))

(deftest update-passes-through-non-settings-keys
  (is (= {:meta 1 :settings {[:a :one] "x"}}
         (registry/update-settings-in-scope
           reg :device
           #(assoc % [:a :one] "x")
           {:meta 1 :settings {}}))))

(deftest update-handles-missing-settings-and-dissoc
  (is (= {:settings {[:a :one] "x"}}
         (registry/update-settings-in-scope
           reg :device #(assoc % [:a :one] "x") {}))
      "f applied over a missing :settings submap")
  (is (= {:settings {[:a :all] 3}}
         (registry/update-settings-in-scope
           reg :device #(dissoc % [:a :one]) {:settings {[:a :one] "old" [:a :all] 3}}))
      "dissoc via f"))


;; ---------------------------------------------------------------------------
;; reconcilable-settings
;; ---------------------------------------------------------------------------

(deftest reconcilable-settings-strips-data-only
  (let [r (registry/->registry
            {:scopes   [{:key :device :persist? true}]
             :settings [{:key [:a :one]  :default "d1"  :scopes #{:device}}
                        {:key [:a :list] :default ["x"] :scopes #{:device} :reconcile? false}]})]
    (is (= {[:a :one] "d1"}
           (registry/reconcilable-settings r {[:a :one] "d1" [:a :list] ["x" "y"]}))
        "data-only settings never reach reconcile")))


;; ---------------------------------------------------------------------------
;; real schema invariant (guards typos in defs.clj)
;; ---------------------------------------------------------------------------

(deftest defs-schema-is-internally-consistent
  (let [scope-keys (set (map :key defs/scopes))
        r          (registry/->registry {:scopes defs/scopes :settings defs/settings})]
    (doseq [s defs/settings]
      (is (vector? (:key s))
          (str (:key s) " must be a path vector"))
      (is (every? scope-keys (:scopes s))
          (str (:key s) " references a scope not in defs/scopes")))
    (is (= (count defs/settings) (count (distinct (map :key defs/settings))))
        "setting keys must be unique")
    (doseq [sk scope-keys]
      (is (seq (registry/scope->allowed-settings r sk))
          (str sk " has no allowed settings")))))
