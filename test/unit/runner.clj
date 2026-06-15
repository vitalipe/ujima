(ns runner
  (:require [clojure.test :as test]
            [ujima.env :as env]
            [lib.task.timeline-test]
            [lib.task.flow-test]
            [lib.task.task-test]

            [ujima.shell-macro-test]

            [lib.edn-test]
            [ujima.env-test]

            [ujima.control.registry-test]))


(def test-namespaces
  '[lib.task.task-test
    lib.task.timeline-test
    lib.task.flow-test

    ujima.shell-macro-test

    lib.edn-test
    ujima.env-test

    ujima.control.registry-test])


(defn -main [& _]

  (env/init! ["config/ujima.edn"
              "config/config.local.edn"])

  (let [{:keys [fail error]} (apply test/run-tests test-namespaces)]
    (when (pos? (+ fail error))
      (System/exit 1))))
