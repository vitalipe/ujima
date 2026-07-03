(ns lib.throttle-test
  (:require [clojure.test :refer [deftest is]]
            [lib.throttle :refer [throttle-leading-trailing]]))


(deftest idle-call-runs-promptly-and-a-burst-coalesces-to-the-trailing-value
  (let [runs* (atom [])
        tf    (throttle-leading-trailing 100 (fn [v] (swap! runs* conj v) v))]
    (is (= {:ok? true :value 1} (deref (tf 1) 1000 ::timeout)))
    (let [ps (doall (map tf (range 2 11)))]                 ; burst inside the window
      (is (every? #(= {:ok? true :value 10} %)
                  (map #(deref % 1000 ::timeout) ps))
          "coalesced calls share one trailing run, newest args win")
      (is (= [1 10] @runs*)))))


(deftest runs-never-overlap-and-the-final-value-lands-last
  ;; f slower (60ms) than the interval (40ms), concurrent callers 15ms apart —
  ;; the drag regime where the old caller-thread leading raced the timer thread.
  (let [active*     (atom 0)
        max-active* (atom 0)
        runs*       (atom [])
        f  (fn [v]
             (swap! max-active* max (swap! active* inc))
             (Thread/sleep 60)
             (swap! runs* conj v)
             (swap! active* dec)
             v)
        tf (throttle-leading-trailing 40 f)
        ps (doall (for [v (range 1 11)]
                    (do (Thread/sleep 15) (future (tf v)))))]
    (doseq [p ps]
      (is (not= ::timeout (deref (deref p 1000 (promise)) 2000 ::timeout))))
    (is (= 1 @max-active*) "f never runs concurrently with itself")
    (is (= 10 (last @runs*)) "the final dragged value is applied last")))


(deftest failures-deliver-into-promises-and-the-throttle-survives
  (let [tf (throttle-leading-trailing 30 (fn [v] (if (= v :boom) (throw (ex-info "boom" {})) v)))]
    (let [r (deref (tf :boom) 1000 ::timeout)]
      (is (false? (:ok? r)))
      (is (= "boom" (ex-message (:error r)))))
    (is (= {:ok? true :value 2} (deref (tf 2) 1000 ::timeout)))))
