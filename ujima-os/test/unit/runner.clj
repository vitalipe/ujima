(ns runner
  (:require [clojure.test :as test]

            [ujima.task.timeline-test]
            [ujima.task.flow-test]
            [ujima.task.task-test]))


(def test-namespaces
  '[ujima.task.task-test
    ujima.task.timeline-test
    ujima.task.flow-test])


(defn -main [& _]
  (let [{:keys [fail error]} (apply test/run-tests test-namespaces)]
    (when (pos? (+ fail error))
      (System/exit 1))))