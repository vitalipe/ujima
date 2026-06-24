(ns runner
  (:require [clojure.test :as test]
            [lib.config :as config]
            [lib.task.timeline-test]
            [lib.task.flow-test]
            [lib.task.task-test]

            [lib.shell-test]
            [lib.shell.command-test]

            [ujima.sudo-test]

            [lib.edn-test]
            [lib.config-test]

            [ujima.control.registry-test]
            [ujima.control.reconcile-test]))


(def test-namespaces
  '[lib.task.task-test
    lib.task.timeline-test
    lib.task.flow-test

    lib.shell-test
    lib.shell.command-test

    ujima.sudo-test

    lib.edn-test
    lib.config-test

    ujima.control.registry-test
    ujima.control.reconcile-test])


(defn -main [& _]

  (config/init! ["config/ujima.edn"
              "config/config.local.edn"])

  (let [{:keys [fail error]} (apply test/run-tests test-namespaces)]
    (when (pos? (+ fail error))
      (System/exit 1))))
