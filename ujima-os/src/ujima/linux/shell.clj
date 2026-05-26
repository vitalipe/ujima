(ns ujima.linux.shell
  (:require [clojure.string  :as str]
            [babashka.process :as p]))            


(defn sh
  "Runs a command. Returns a result map. Does not throw."
  [cmd & args]
  (let [result (apply p/shell {:out :string
                               :err :string
                               :continue true}
                      (name cmd)
                      args)]

    {:ok?   (zero? (:exit result))
     :exit  (:exit result)
     :out   (str/trim (:out result))
     :err   (str/trim (:err result))}))


(defn sudo
  [cmd & args]
  (apply sh :sudo "-n" (name cmd) args))


(defn sh!
  "Runs a command. Throws on non-zero exit."
  [cmd & args]
  (let [result (apply sh cmd args)]
    (when-not (:ok? result)
      (throw
        (ex-info (str "Command failed: " cmd args)
                 result)))
    result))


(defn sudo!
  "Runs sudo command. Throws on non-zero exit."
  [cmd & args]
  (apply sh! :sudo "-n" (name cmd) args))
