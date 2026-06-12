(ns ujima.linux.token
  (:require
            [clojure.core.async :as a]
            [clojure.java.io :as java-io]

            [babashka.process :as p]

            [ujima.fs  :refer [probe-file!]]))


(defn do-probe-control-token! [_]
  (let [control-file (probe-file! "/media" "*/*/.ujima-control-token")]
    (cond
      (nil? control-file) {:present? false}
      :token-file-found   {:present? true
                           :type :usb
                           :file control-file})))


(defn watch-control-token! [env]
  (let [ch* (a/chan (a/sliding-buffer 1))
        proc (p/process ["udevadm" "monitor" "--udev" "--subsystem-match=block"]
                        {:out :stream
                         :err :stream})]

    ;; Emit initial state immediately.
    (a/>!! ch* (do-probe-control-token! env))

    (a/thread
      (try
        (with-open [reader (java-io/reader (:out proc))]
          (loop [last-token (do-probe-control-token! env)]
            (when-let [_line (.readLine reader)]

              ;; USB mount may not be ready at exact udev event time.
              ;; Small delay lets udisks/systemd/desktop automount finish.
              (Thread/sleep 800)

              (let [token (do-probe-control-token! env)]
                (if (= token last-token) ;; ignore dup token states
                  (recur last-token)
                  (when (a/>!! ch* token) ;; recur when ch* still open
                    (recur token)))))))
        (finally
          (p/destroy-tree proc))))

    ch*))
