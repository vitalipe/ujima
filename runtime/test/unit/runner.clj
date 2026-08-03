(ns runner
  (:require [clojure.test :as test]
            [lib.task.timeline-test]
            [lib.task.flow-test]
            [lib.task.task-test]

            [lib.shell-test]
            [lib.shell.command-test]
            [lib.throttle-test]

            [ujima.sudo-test]

            [lib.edn-test]
            [lib.io-test]

            [ujima.control.registry-test]
            [ujima.linux.converge-test]
            [ujima.control-test]
            [ujima.control.commands-test]
            [ujima.control.queries-test]

            [ujima.linux.audio-test]
            [ujima.linux.i3-test]
            [ujima.linux.systemd-test]
            [ujima.desktop.http-test]
            [ujima.desktop.http.ui-test]
            [ujima.desktop.app.catalog-test]
            [ujima.desktop.app-test]
            [ujima.desktop.eww-test]
            [ujima.events.audio-test]
            [ujima.events.token-test]))


(def test-namespaces
  '[lib.task.task-test
    lib.task.timeline-test
    lib.task.flow-test

    lib.shell-test
    lib.shell.command-test
    lib.throttle-test

    ujima.sudo-test

    lib.edn-test
    lib.io-test

    ujima.control.registry-test
    ujima.linux.converge-test
    ujima.control-test
    ujima.control.commands-test
    ujima.control.queries-test

    ujima.linux.audio-test
    ujima.linux.i3-test
    ujima.linux.systemd-test
    ujima.desktop.http-test
    ujima.desktop.http.ui-test
    ujima.desktop.app.catalog-test
    ujima.desktop.app-test
    ujima.desktop.eww-test
    ujima.events.audio-test
    ujima.events.token-test])


(defn -main [& _]
  (let [{:keys [fail error]} (apply test/run-tests test-namespaces)]
    (when (pos? (+ fail error))
      (System/exit 1))))
