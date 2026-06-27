(ns ujima.core
  (:require [lib.io                  :as io]
            [ujima.log               :as log]

            [ujima.device  :as device]
            [ujima.control :as control]
            [lib.shell :as shell]

            [ujima.desktop :as desktop]
            [ujima.agent   :as agent]))



(defn -main [& args]

  (let [env (io/slurp-config "config" "ujimad")]

    (shell/install-remap! (get-in env [:shell :commands] {}))

    (log/init!     (get-in env [:log]     {:level :info}))
    (control/init! (get-in env [:control] {}))
    (desktop/init! (get-in env [:desktop] {}))
    (agent/init!   (get-in env [:agent]   {}))))

