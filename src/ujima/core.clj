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

    ;; explicit boot order: reconcile settings FIRST, then bring up the desktop (subscribe, API,
    ;; eww, seed) so the shell that appears is the finished, reconciled desktop. Agent loop blocks last.
    (control/init!     (get-in env [:control] {}))
    (control/reconcile!)
    (desktop/init!     (get-in env [:desktop] {}))
    (agent/init!       (get-in env [:agent]   {}))))

