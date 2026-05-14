(ns ujima.runtime.target.rpi
  (:require 
            [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.java.io :as java-io]

            [babashka.process :as p]            

            [ujima.log :as log]
            [ujima.io :refer [sudo! sh! spit-file-atomic! slurp-edn! probe-file!]]
            [ujima.runtime.protocols :refer [UjimaSystem UjimaDesktop UjimaDiscovery UjimaRuntime]]))


(defn- do-probe-control-token! [_]
  (let [control-file (probe-file! "/media" "*/*/.ujima-control-token")]
    (cond
      (nil? control-file) {:present? false}
      :token-file-found   {:present? true 
                           :type :usb 
                           :file control-file})))
      

(defrecord RpiRuntime [env]

  UjimaSystem
  (hostname [_]
    (:out (sh! :hostnamectl "--static")))


  (hostname! [_ hostname]
    (sudo! :hostnamectl "set-hostname" hostname)
    (:out (sh! :hostnamectl "--static")))


  (timezone [_]
    (:out (sh! :timedatectl "show" "-p" "Timezone" "--value")))


  (timezone! [_ timezone]
    (sudo! :timedatectl "set-timezone" timezone)
    (:out (sh! :timedatectl "show" "-p" "Timezone" "--value")))
  

  (keyboard-layouts [_])
    ;; TODO: test localectl status


  (keyboard-layouts! [_ layouts]
    (sudo! :localectl "set-x11-keymap" (str/join "," layouts)))


  (reboot! [_]
    (sudo! :systemctl "reboot"))


  (shutdown! [_]
    (sudo! :systemctl "poweroff")) 


  UjimaDesktop

  (volume [_]
    (let [out (:out (sh! :pactl "get-sink-volume" "@DEFAULT_SINK@"))]
      (Integer/parseInt (second (re-find #"(\d+)%" out)))))


  (volume! [this value]
    (let [value (-> value int (max 0) (min 100))]
      (sh! :pactl "set-sink-volume" "@DEFAULT_SINK@" (str value "%"))
      
      ;; get volume
      (let [out (:out (sh! :pactl "get-sink-volume" "@DEFAULT_SINK@"))]
        (Integer/parseInt (second (re-find #"(\d+)%" out))))))


  (wallpaper [_])
    ;; TODO once desktop is stable

  (wallpaper! [_ path])
    ;; TODO once desktop is stable


  (screen-locked? [_])
    ;; TODO once desktop is stable

  (screen-lock! [_])
    ;; TODO once desktop is stable

  (screen-unlock! [_])
    ;; TODO once desktop is stable

  (app-list [_])
    ;; TODO once desktop is stable


  (app-info [_ name])
    ;; TODO once desktop is stable


  (app-start! [_ name args])
    ;; TODO once desktop is stable


  (app-kill! [_ name])
    ;; TODO once desktop is stable


  UjimaDiscovery
  (discover-peers! [_ _opts]
    ;; Placeholder. Later: avahi-browse / mDNS helper.
    [])

  (discover-content! [_ _opts]
    ;; Placeholder. Later: local content manifest discovery.
    [])


  UjimaRuntime
  
  (settings [_]
    (let [path (get-in env [:paths :settings] "/var/lib/ujima/settings.edn")]
      (slurp-edn! path {})))


  (settings! [_ settings]
    (let [path         (get-in env [:paths :settings] "/var/lib/ujima/settings.edn")
          write-result (spit-file-atomic! path (pr-str settings))]
     
     (when-not (:ok? write-result)
       (log/error "Failed to write settings" write-result))

     (slurp-edn! path {})))


  (probe-control-token [_]
    (do-probe-control-token! env))


  (watch-control-token! [this]
    (let [ch* (a/chan (a/sliding-buffer 1))
          proc (p/process ["udevadm" "monitor" "--udev" "--subsystem-match=block"]
                          {:out :stream
                           :err :stream})]

      ;; Emit initial state immediately.
      (a/>!! ch* (do-probe-control-token! env))

      (a/thread
        (try
          (with-open [reader (java-io/reader (:out proc))]
            (loop [last-token (do-probe-control-token! env)]
              (when-let [_line (.readLine reader)]
                ;; USB mount may not be ready at exact udev event time.
                ;; Small delay lets udisks/systemd/desktop automount finish.
                (Thread/sleep 800)

                (let [token (do-probe-control-token! env)]
                  (if (= token last-token)
                    (recur last-token)
                    (when (a/>!! ch* token)
                      (recur token)))))))
          (finally
            (p/destroy-tree proc))))

      ch*)))

(defn ->runtime [env]
  (->RpiRuntime env))