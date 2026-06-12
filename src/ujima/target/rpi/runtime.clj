;; death row: removed in phase 3 (control module)
(ns ujima.target.rpi.runtime
  (:require
            [ujima.log :as log]
            [ujima.fs  :refer [spit-file-atomic
                               slurp-edn]]

            [ujima.linux.system     :as linux]
            [ujima.linux.desktop    :as desktop]
            [ujima.linux.token      :as token]
            [ujima.runtime.protocol :refer [UjimaSystem
                                            UjimaDesktop
                                            UjimaRuntime]]))


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

  (volume  [_]       (desktop/volume))
  (volume! [_ value] (desktop/volume! value))
  

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
      (slurp-edn path {})))


  (settings! [_ settings]
    (let [path         (get-in env [:paths :settings] "/var/lib/ujima/runtime.edn")
          write-result (spit-file-atomic path (pr-str settings))]
     
     (when-not (:ok? write-result)
       (log/error "Failed to write settings" write-result))

     (slurp-edn path {})))


  (probe-control-token [_]
    (token/do-probe-control-token! env))


  (watch-control-token! [this]
    (token/watch-control-token! env)))


(defn ->runtime [env]
  (->RpiRuntime env))
