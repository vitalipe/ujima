(ns ujima.reconcile-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.reconcile :as reconcile]))


;; Tests the converge decision logic only (no OS): each test swaps `handlers`
;; for stub :get/:set fns, where :set records the value it was called with.
;; Keys are path vectors, the system's key shape.


(deftest skips-setter-when-already-in-sync
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {[:a :k] {:get (constantly "v") :set #(swap! calls conj %)}}]
      (reconcile/converge! {[:a :k] "v"}))
    (is (= [] @calls))))


(deftest applies-setter-with-desired-when-different
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {[:a :k] {:get (constantly "old") :set #(swap! calls conj %)}}]
      (reconcile/converge! {[:a :k] "new"}))
    (is (= ["new"] @calls))))


(deftest set-only-handler-without-getter-always-applies
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {[:a :k] {:get nil :set #(swap! calls conj %)}}]
      (reconcile/converge! {[:a :k] "x"}))
    (is (= ["x"] @calls))))


(deftest falsy-desired-is-applied-when-different
  ;; [:audio :muted] false must converge (regression: falsy not treated as absent)
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {[:audio :muted] {:get (constantly true) :set #(swap! calls conj %)}}]
      (reconcile/converge! {[:audio :muted] false}))
    (is (= [false] @calls))))


(deftest falsy-desired-in-sync-is-skipped
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {[:audio :muted] {:get (constantly false) :set #(swap! calls conj %)}}]
      (reconcile/converge! {[:audio :muted] false}))
    (is (= [] @calls))))


(deftest failing-setter-is-caught-and-others-still-converge
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {[:a :bad]  {:get (constantly "old") :set (fn [_] (throw (ex-info "boom" {})))}
                   [:a :good] {:get (constantly "old") :set #(swap! calls conj %)}}]
      (is (= {[:a :bad] "x" [:a :good] "y"}
             (reconcile/converge! {[:a :bad] "x" [:a :good] "y"}))
          "converge! does not throw and returns settings")
      (is (= ["y"] @calls)
          "the good setting still converged despite the bad one throwing"))))


(deftest settings-without-a-handler-are-not-ours-to-apply
  ;; data-only settings (:reconcile? false) arrive in the full effective map
  (let [calls (atom [])]
    (with-redefs [reconcile/handlers
                  {[:a :known] {:get (constantly "old") :set #(swap! calls conj %)}}]
      (reconcile/converge! {[:a :known] "new" [:a :unknown] "z"}))
    (is (= ["new"] @calls))))


(deftest converge-target-proves-handler-coverage
  (with-redefs [reconcile/handlers {[:a :k] {:get nil :set identity}}]
    (is (= reconcile/converge!
           (reconcile/converge-target [{:key [:a :k]}
                                       {:key [:a :data] :reconcile? false}]))
        "covered defs (data-only excluded) -> the port fn")
    (is (= {:missing-handlers [[:a :gap]] :orphan-handlers []}
           (try (reconcile/converge-target [{:key [:a :k]} {:key [:a :gap]}])
                (catch Exception e (ex-data e))))
        "reconcilable def without a handler fails assembly")
    (is (= {:missing-handlers [] :orphan-handlers [[:a :k]]}
           (try (reconcile/converge-target [{:key [:b :other]  :reconcile? false}])
                (catch Exception e (ex-data e))))
        "handler without a def fails assembly too")))
