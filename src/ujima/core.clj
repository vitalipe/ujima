(ns ujima.core
  (:require [ujima.env               :as env]
            [ujima.log               :as log]

            [ujima.device  :as device]
            [ujima.control :as control]
            [ujima.linux.sudo :as sudo]

            [ujima.agent   :as agent]))



(defn -main [& args]

  (env/init! ["config/ujima.edn"
              "config/config.local.edn"])

  (sudo/install-remap! (env/get-in-env [:shell :commands] {}))

  (log/init!          (env/get-in-env [:log] {:level :info}))
  (control/init!      (env/get-in-env [:control] {}))

  (agent/init! (env/get-in-env [:agent] {})))

