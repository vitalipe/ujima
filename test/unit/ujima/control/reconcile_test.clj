(ns ujima.control.reconcile-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.control.reconcile :as reconcile]))


;; Tests the converge decision logic only (no OS): each test swaps `handlers`
;; for stub :get/:set fns, where :set records the value it was called with.


(deftest skips-setter-when-already-in-sync
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {:k {:get (constantly "v") :set #(swap! calls conj %)}}]
      (reconcile/reconcile! {:k "v"}))
    (is (= [] @calls))))


(deftest applies-setter-with-desired-when-different
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {:k {:get (constantly "old") :set #(swap! calls conj %)}}]
      (reconcile/reconcile! {:k "new"}))
    (is (= ["new"] @calls))))


(deftest set-only-handler-without-getter-always-applies
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {:k {:get nil :set #(swap! calls conj %)}}]
      (reconcile/reconcile! {:k "x"}))
    (is (= ["x"] @calls))))


(deftest falsy-desired-is-applied-when-different
  ;; :audio/muted false must converge (regression: falsy not treated as absent)
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {:audio/muted {:get (constantly true) :set #(swap! calls conj %)}}]
      (reconcile/reconcile! {:audio/muted false}))
    (is (= [false] @calls))))


(deftest falsy-desired-in-sync-is-skipped
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {:audio/muted {:get (constantly false) :set #(swap! calls conj %)}}]
      (reconcile/reconcile! {:audio/muted false}))
    (is (= [] @calls))))


(deftest failing-setter-is-caught-and-others-still-converge
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {:bad  {:get (constantly "old") :set (fn [_] (throw (ex-info "boom" {})))}
                   :good {:get (constantly "old") :set #(swap! calls conj %)}}]
      (is (= {:bad "x" :good "y"}
             (reconcile/reconcile! {:bad "x" :good "y"}))
          "reconcile! does not throw and returns settings")
      (is (= ["y"] @calls)
          "the good setting still converged despite the bad one throwing"))))


(deftest unknown-setting-is-skipped
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {:known {:get (constantly "old") :set #(swap! calls conj %)}}]
      (reconcile/reconcile! {:known "new" :unknown "z"}))
    (is (= ["new"] @calls))))
