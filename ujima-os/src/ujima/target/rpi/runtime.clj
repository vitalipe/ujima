(ns ujima.target.rpi.runtime
  (:require 
            [clojure.core.async :as a]
            [clojure.java.io :as java-io]

            [babashka.process :as p]            

            [ujima.log :as log]
            [ujima.fs  :refer [spit-file-atomic! 
                               slurp-edn! 
                               probe-file!]]

            [ujima.linux.system     :as linux]
            [ujima.runtime.protocol :refer [UjimaSystem 
                                            UjimaDesktop 
                                            UjimaRuntime]]))


(defn- do-probe-control-token! [_]
  (let [control-file (probe-file! "/media" "*/*/.ujima-control-token")]
    (cond
      (nil? control-file) {:present? false}
      :token-file-found   {:present? true 
                           :type :usb 
                           :file control-file})))
      

(defrecord RpiRuntime [env]

  UjimaSystem
  
  (hostname  [_]          (linux/hostname))  
  (hostname! [_ hostname] (linux/hostname! hostname))  
  
  (timezone  [_]          (linux/timezone))
  (timezone! [_ timezone] (linux/timezone! timezone))
  
  (keyboard-layouts [_]          (linux/keyboard-layouts))
  (keyboard-layouts! [_ layouts] (linux/keyboard-layouts! layouts))
    
  (reboot!   [_] (linux/reboot!))
  (shutdown! [_] (linux/shutdown!))
   
  UjimaDesktop

  (volume  [_]       (linux/volume))
  (volume! [_ value] (linux/volume! value))
  

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


  UjimaRuntime
  
  (settings [_]
    (let [path (get-in env [:paths :settings] "/var/lib/ujima/runtime.edn")]
      (slurp-edn! path {})))


  (settings! [_ settings]
    (let [path         (get-in env [:paths :settings] "/var/lib/ujima/runtime.edn")
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
                  (if (= token last-token) ;; ignore dup token states
                    (recur last-token) 
                    (when (a/>!! ch* token) ;; recur when ch* still open
                      (recur token)))))))
          (finally
            (p/destroy-tree proc))))

      ch*)))


(defn ->runtime [env]
  (->RpiRuntime env))
