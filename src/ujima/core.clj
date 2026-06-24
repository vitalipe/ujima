(ns ujima.core
  (:require [lib.config              :as config]
            [ujima.log               :as log]

            [ujima.device  :as device]
            [ujima.control :as control]
            [lib.shell :as shell]

            [ujima.agent   :as agent]))



(defn -main [& args]

  (config/init! ["config/ujima.edn"
              "config/config.local.edn"])

  (shell/install-remap! (config/get-in-env [:shell :commands] {}))

  (log/init!          (config/get-in-env [:log] {:level :info}))
  (control/init!      (config/get-in-env [:control] {}))

  (agent/init! (config/get-in-env [:agent] {})))

