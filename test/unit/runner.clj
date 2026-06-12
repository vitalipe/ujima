(ns runner
  (:require [clojure.test :as test]
            [ujima.env :as env]
            [ujima.task.timeline-test]
            [ujima.task.flow-test]
            [ujima.task.task-test]

            [ujima.shell-macro-test]

            [ujima.edn-test]
            [ujima.env-test]))


(def test-namespaces
  '[ujima.task.task-test
    ujima.task.timeline-test
    ujima.task.flow-test

    ujima.shell-macro-test

    ujima.edn-test
    ujima.env-test])


(defn -main [& _]

  (env/init! ["config/ujima.edn"
              "config/config.local.edn"])

  (let [{:keys [fail error]} (apply test/run-tests test-namespaces)]
    (when (pos? (+ fail error))
      (System/exit 1))))
