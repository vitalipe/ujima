(ns ujima.linux.converge-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.linux.converge :as converge]))


;; Tests the converge decision logic only (no OS): each test swaps `handlers`
;; for stub :get/:set fns, where :set records the value it was called with.
;; Keys are path vectors, the system's key shape. prv = nil means "assume
;; nothing" (boot/udev); tests that aren't about prv-gating use it.


(deftest skips-setter-when-already-in-sync
  (let [calls (atom [])]
    (with-redefs [converge/handlers
                  {[:a :k] {:get (constantly "v") :set #(swap! calls conj %)}}]
      (converge/converge! {[:a :k] "v"} nil))
    (is (= [] @calls))))


(deftest applies-setter-with-desired-when-different
  (let [calls (atom [])]
    (with-redefs [converge/handlers
                  {[:a :k] {:get (constantly "old") :set #(swap! calls conj %)}}]
      (converge/converge! {[:a :k] "new"} nil))
    (is (= ["new"] @calls))))


(deftest set-only-handler-without-getter-always-applies
  (let [calls (atom [])]
    (with-redefs [converge/handlers
                  {[:a :k] {:get nil :set #(swap! calls conj %)}}]
      (converge/converge! {[:a :k] "x"} nil))
    (is (= ["x"] @calls))))


(deftest falsy-desired-is-applied-when-different
  ;; [:audio :muted] false must converge (regression: falsy not treated as absent)
  (let [calls (atom [])]
    (with-redefs [converge/handlers
                  {[:audio :muted] {:get (constantly true) :set #(swap! calls conj %)}}]
      (converge/converge! {[:audio :muted] false} nil))
    (is (= [false] @calls))))


(deftest failing-setter-is-caught-and-others-still-converge
  (let [calls (atom [])]
    (with-redefs [converge/handlers
                  {[:a :bad]  {:get (constantly "old") :set (fn [_] (throw (ex-info "boom" {})))}
                   [:a :good] {:get (constantly "old") :set #(swap! calls conj %)}}]
      (is (= {[:a :bad] "x" [:a :good] "y"}
             (converge/converge! {[:a :bad] "x" [:a :good] "y"} nil))
          "converge! does not throw and returns settings")
      (is (= ["y"] @calls)
          "the good setting still converged despite the bad one throwing"))))


(deftest settings-without-a-handler-are-not-linux-business
  (let [calls (atom [])]
    (with-redefs [converge/handlers
                  {[:a :known] {:get (constantly "old") :set #(swap! calls conj %)}}]
      (converge/converge! {[:a :known] "new" [:a :unknown] "z"} nil))
    (is (= ["new"] @calls))))


(deftest prv-gates-untouched-keys-entirely
  ;; a write-converge must not even READ the OS for keys that didn't change
  (let [gets (atom 0)
        sets (atom [])]
    (with-redefs [converge/handlers
                  {[:a :same]    {:get (fn [] (swap! gets inc) "x")   :set #(swap! sets conj %)}
                   [:a :changed] {:get (fn [] (swap! gets inc) "old") :set #(swap! sets conj %)}}]
      (converge/converge! {[:a :same] "x" [:a :changed] "new"}
                          {[:a :same] "x" [:a :changed] "old"}))
    (is (= 1 @gets)       "the unchanged key cost zero OS reads")
    (is (= ["new"] @sets) "only the changed key converged")))


(deftest nil-prv-sweeps-everything
  (let [gets (atom 0)]
    (with-redefs [converge/handlers
                  {[:a :one] {:get (fn [] (swap! gets inc) "v") :set identity}
                   [:a :two] {:get (fn [] (swap! gets inc) "v") :set identity}}]
      (converge/converge! {[:a :one] "v" [:a :two] "v"} nil))
    (is (= 2 @gets) "boot/udev: assume nothing, getter-compare all")))
