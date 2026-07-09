(ns ujima.core
  (:require [lib.io                  :as io]
            [ujima.log               :as log]

            [ujima.device         :as device]
            [ujima.control        :as control]
            [ujima.linux.converge :as linux]
            [lib.shell :as shell]

            [ujima.desktop          :as desktop]
            [ujima.desktop.http.ui  :as ui]
            [ujima.desktop.http.app :as apps]
            [ujima.desktop.app      :as app]
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
    ;; cold-boot X-auth guard: wait for X to accept an authorized connection before the first
    ;; converge (keyboard) or eww touch it — else they race startx's cookie write and the shell dies.
    (desktop/await-x!)
    (control/converge-fresh!)

    ;; the catalog (the window-adoption class index) must exist before the i3 watcher's
    ;; baseline lands; a missing catalog is a broken image — die loudly here
    (app/load-catalog! (get-in env [:desktop :catalog]))
    (app/set-push!     apps/push!)
    (app/set-bars!     (desktop/bars-control (get-in env [:desktop] {})))
    (events/init!      (get-in env [:events]  {}))

    ;; desktop/init! BLOCKS holding eww; it coming back means the shell died. Exit explicitly —
    ;; agent threads must not keep bb alive, or the wrapper's `i3-msg exit` never runs and
    ;; systemd can't cold-rebuild the session.
    (try (desktop/init! (get-in env [:desktop] {}))
         (catch Throwable e (log/error "shell died" {:error (ex-message e)})))
    (System/exit 1)))
