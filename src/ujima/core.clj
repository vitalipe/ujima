(ns ujima.core
  (:require [lib.io                  :as io]
            [ujima.log               :as log]

            [ujima.device         :as device]
            [ujima.control        :as control]
            [ujima.linux.converge :as linux]
            [lib.shell :as shell]

            [ujima.desktop    :as desktop]
            [ujima.desktop.ui :as ui]
            [ujima.events      :as events]))



(defn -main [& args]

  (let [env (io/slurp-config "config" "ujimad")]

    (shell/install-remap! (get-in env [:shell :commands] {}))

    (log/init!     (get-in env [:log]     {:level :info}))

    ;; explicit boot order: converge settings first, start the agent loop (returns), then hand
    ;; the main thread to the shell — so the desktop that appears is the converged one.
    ;; control drives its converge ports in vector order: linux first, then the GUI.
    (control/init!     (assoc (get-in env [:control] {})
                              :converge-targets [linux/converge! ui/converge!]))
    (control/converge-fresh!)
    (events/init!      (get-in env [:events]  {}))

    ;; desktop/init! BLOCKS holding eww; it coming back means the shell died. Exit explicitly —
    ;; agent threads must not keep bb alive, or the wrapper's `i3-msg exit` never runs and
    ;; systemd can't cold-rebuild the session.
    (try (desktop/init! (get-in env [:desktop] {}))
         (catch Throwable e (log/error "shell died" {:error (ex-message e)})))
    (System/exit 1)))
