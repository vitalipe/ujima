(ns ujima.cli
  (:require
    [ujima.env :as env]
    [lib.cli :as cli]
    [ujima.runtime.protocol :as runtime]
    [ujima.runtime.settings :as settings]
    [ujima.device :refer [->runtime]]
    [ujima.log :as log]
    [ujima.fs :refer [slurp-edn]]))

;; ----------------------------------------------------------------------------
;; Runtime command dispatch
;; ----------------------------------------------------------------------------

(defn dispatch-cli
  [runtime* cmd & args]
  (case cmd
    "hostname"
    (let [[hostname] args]
      (if hostname
        (println (settings/hostname+settings! runtime* hostname))
        (println (runtime/hostname runtime*))))

    "timezone"
    (let [[timezone] args]
      (if timezone
        (println (settings/timezone+settings! runtime* timezone))
        (println (runtime/timezone runtime*))))

    "keyboard-layouts"
    (if (seq args)
      (println (settings/keyboard-layouts+settings! runtime* args))
      (println (runtime/keyboard-layouts runtime*)))

    "volume"
    (let [[volume] args]
      (if (some? volume)
        (println (runtime/volume! runtime* (int volume)))
        (println (runtime/volume runtime*))))

    "control-token"
    (println (runtime/probe-control-token runtime*))

    "reboot"
    (println (runtime/reboot! runtime*))

    "shutdown"
    (println (runtime/shutdown! runtime*))

    (throw
      (ex-info "Unknown runtime command"
               {:cmd cmd
                :args args}))))

;; ----------------------------------------------------------------------------
;; Main
;; ----------------------------------------------------------------------------

(defn -main
  [& args]

  (env/init! ["config/ujima.edn" "config/config.local.edn"])
  (log/set-log-level! :report)

  (let [runtime* (->runtime (env/get-in-env [:runtime]))
        command-tree {"runtime"
                       {"hostname"
                        {:usage "Usage: ujima runtime hostname [hostname]"
                         :target #(dispatch-cli runtime* "hostname" (:hostname %))
                         :args [:hostname]
                         :spec {:hostname {:desc "Hostname to set"}}}

                        "timezone"
                        {:usage "Usage: ujima runtime timezone [timezone]"
                         :target #(dispatch-cli runtime* "timezone" (:timezone %))
                         :args [:timezone]
                         :spec {:timezone {:desc "Timezone to set, e.g. Asia/Jerusalem"}}}

                        "keyboard-layouts"
                        {:usage "Usage: ujima runtime keyboard-layouts [layout ...]"
                         :target #(apply dispatch-cli
                                         runtime*
                                         "keyboard-layouts"
                                         (remove nil?
                                                 (cons (:layout %)
                                                       (:extra-args %))))
                         :args [:layout]
                         :allow-extra-args? true
                         :spec {:layout {:desc "Keyboard layout to set. Additional layouts may be passed as extra positional args."}}}

                        "volume"
                        {:usage "Usage: ujima runtime volume [volume]"
                         :target #(dispatch-cli runtime* "volume" (:volume %))
                         :args [:volume]
                         :spec {:volume {:desc "Volume from 0 to 100"
                                         :coerce :long
                                         :validate #(<= 0 % 100)}}}

                        "control-token"
                        {:usage "Usage: ujima runtime control-token"
                         :target (fn [_] (dispatch-cli runtime* "control-token"))
                         :args []
                         :spec {}}

                        "reboot"
                        {:usage "Usage: ujima runtime reboot"
                         :target (fn [_] (dispatch-cli runtime* "reboot"))
                         :args []
                         :spec {}}

                        "shutdown"
                        {:usage "Usage: ujima runtime shutdown"
                         :target (fn [_] (dispatch-cli runtime* "shutdown"))
                         :args []
                         :spec {}}}}]


    (cli/dispatch! command-tree args)))
