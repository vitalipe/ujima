(ns ujima.core
  (:require [lib.io                  :as io]
            [ujima.log               :as log]

            [ujima.device  :as device]
            [ujima.control :as control]
            [lib.shell :as shell]

            [ujima.agent   :as agent]))



(defn -main [& args]

  (let [env (io/slurp-config "config" "ujima")]

    (shell/install-remap! (get-in env [:shell :commands] {}))

    (log/init!     (get-in env [:log]     {:level :info}))
    (control/init! (get-in env [:control] {}))
    (agent/init!   (get-in env [:agent]   {}))))

