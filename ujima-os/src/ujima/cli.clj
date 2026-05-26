(ns ujima.cli
  (:require [ujima.runtime.protocol  :as runtime]
            [ujima.target            :refer [->runtime]] 
            [ujima.runtime.settings  :as settings]

            [ujima.log               :as log] 
            [ujima.fs                :refer [slurp-edn]]))


(defn usage []
  (println "Usage:")
  (println "  ujima hostname")
  (println "  ujima hostname <hostname>")
  (println "  ujima timezone")
  (println "  ujima timezone <timezone>")
  (println "  ujima keyboard-layouts")
  (println "  ujima keyboard-layouts <layout>...")
  (println "  ujima volume")
  (println "  ujima volume <0-100>")
  (println "  ujima control-token")
  (println "  ujima reboot")
  (println "  ujima shutdown"))


(defn parse-int [s]
  (Integer/parseInt s))


(defn run! [runtime* args]
  (let [[cmd & rest] args]
    (case cmd
      "hostname"
      (if (seq rest)
        (println (settings/hostname+settings! runtime* (first rest)))
        (println (runtime/hostname runtime*)))

      "timezone"
      (if (seq rest)
        (println (settings/timezone+settings! runtime* (first rest)))
        (println (runtime/timezone runtime*)))

      "keyboard-layouts"
      (if (seq rest)
        (println (settings/keyboard-layouts+settings! runtime* rest))
        (println (runtime/keyboard-layouts runtime*)))

      "volume"
      (if (seq rest)
        (println (runtime/volume! runtime* (parse-int (first rest))))
        (println (runtime/volume runtime*)))

      "control-token"
      (println (runtime/probe-control-token runtime*))

      "reboot"
      (println (runtime/reboot! runtime*))

      "shutdown"
      (println (runtime/shutdown! runtime*))

      (usage))))

                                 
(defn -main [& args]
  (let [[env-path & rest-args] args
        env                   (slurp-edn env-path {})]

    (log/set-log-level! :report)
    (run! (->runtime env) rest-args)))
