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

    ;; explicit boot order: reconcile settings first, start the agent loop (returns), then hand
    ;; the main thread to the shell — so the desktop that appears is the reconciled one.
    (control/init!     (get-in env [:control] {}))
    (control/reconcile!)
    (agent/init!       (get-in env [:agent]   {}))

    ;; desktop/init! BLOCKS holding eww; it coming back means the shell died. Exit explicitly —
    ;; agent threads must not keep bb alive, or the wrapper's `i3-msg exit` never runs and
    ;; systemd can't cold-rebuild the session.
    (try (desktop/init! (get-in env [:desktop] {}))
         (catch Throwable e (log/error "shell died" {:error (ex-message e)})))
    (System/exit 1)))
