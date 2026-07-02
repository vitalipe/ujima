(ns ujima.control.reconcile-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.control.reconcile :as reconcile]))


;; Tests the converge decision logic only (no OS): each test swaps `handlers`
;; for stub :get/:set fns, where :set records the value it was called with.
;; Keys are path vectors, the system's key shape.


(deftest skips-setter-when-already-in-sync
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {[:a :k] {:get (constantly "v") :set #(swap! calls conj %)}}]
      (reconcile/reconcile! {[:a :k] "v"}))
    (is (= [] @calls))))


(deftest applies-setter-with-desired-when-different
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {[:a :k] {:get (constantly "old") :set #(swap! calls conj %)}}]
      (reconcile/reconcile! {[:a :k] "new"}))
    (is (= ["new"] @calls))))


(deftest set-only-handler-without-getter-always-applies
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {[:a :k] {:get nil :set #(swap! calls conj %)}}]
      (reconcile/reconcile! {[:a :k] "x"}))
    (is (= ["x"] @calls))))


(deftest falsy-desired-is-applied-when-different
  ;; [:audio :muted] false must converge (regression: falsy not treated as absent)
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {[:audio :muted] {:get (constantly true) :set #(swap! calls conj %)}}]
      (reconcile/reconcile! {[:audio :muted] false}))
    (is (= [false] @calls))))


(deftest falsy-desired-in-sync-is-skipped
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {[:audio :muted] {:get (constantly false) :set #(swap! calls conj %)}}]
      (reconcile/reconcile! {[:audio :muted] false}))
    (is (= [] @calls))))


(deftest failing-setter-is-caught-and-others-still-converge
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {[:a :bad]  {:get (constantly "old") :set (fn [_] (throw (ex-info "boom" {})))}
                   [:a :good] {:get (constantly "old") :set #(swap! calls conj %)}}]
      (is (= {[:a :bad] "x" [:a :good] "y"}
             (reconcile/reconcile! {[:a :bad] "x" [:a :good] "y"}))
          "reconcile! does not throw and returns settings")
      (is (= ["y"] @calls)
          "the good setting still converged despite the bad one throwing"))))


(deftest unknown-setting-is-skipped
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {[:a :known] {:get (constantly "old") :set #(swap! calls conj %)}}]
      (reconcile/reconcile! {[:a :known] "new" [:a :unknown] "z"}))
    (is (= ["new"] @calls))))
