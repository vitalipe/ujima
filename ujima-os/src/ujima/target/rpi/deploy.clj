(ns ujima.target.rpi.deploy
  (:require 

            [ujima.linux.disk      :as disk
                                   :refer [device->partitions
                                           with-mounted-vfat]]
            [ujima.deploy.protocol :refer [UjimaDeployTarget]]
            [ujima.deploy.pack     :as pack]

            [ujima.target.rpi.autoboot   :as autoboot]
            [ujima.target.rpi.partitions :as partitions]))

(comment

   tasks will be the main abstaction unit of the cli+tui+gui
   they are a structed log with a state 

   ;; constactor
   (->ujima-task "task-name" f*)

   ;; f* gets UjimaTask record

   
   (task/log! task* type payload) ;; write a message

   (task/progress! task* 10 "message") ;; (task/log! :progress {:progress 10 :message "message"})

   (task/fail! task* "error")      ;; (do
                                   ;;   (task/log! :error {:message "error"}))
                                   ;;   close intarnal ch


   (task/done!     task value)     ;; (do
                                   ;;   (task/log! :done {:value value :message "done!"}))
                                   ;;   close intarnal ch with value

   (task/join! task* another-task*)

   (task/log task) ;; a lazy seq of task "events"    





   (with-progress [progress!]

     (progress! 10 "wow!")
     (let [child* (fn-that-has-its-own-progress)])
     (join! progress! child* 10)) ;; this tells park until child* is closed (error or done) 
                                   ;; 2nd arg (50) tells that it will take 50% of the parent process
                                   
     ;; once we reach the end of this block put (progress! 100)

  
  (defrecord ProgressTask [ch state*]))





(defrecord RpiDeploy [env]

  UjimaDeployTarget

  (ujima-boot-info [this target-device])


  (install-ujima! [this ujima-pack-path target-device]
    "Install Ujima OS onto target-device from ujima-pack-path.

     This is destructive.

     It creates the A/B partition layout, writes the initial Ujima OS pack into
     the first install slot, and prepares the device to boot Ujima OS.

     This should only be used for fresh installs, image creation, or explicit
     full-device reinstall.

     Returns a core.async channel of progress events."

    (partitions/write-ab-partition-layout! target-device)

    (with-mounted-vfat [ctl (first (device->partitions target-device))]
      (autoboot/autoboot! ctl {:boot 2})))


  (upgrade-ujima! [this ujima-pack-path target-device]
  
   ;; (require-ab-partition-layout! target-device)
   
   ;; detect inactive slot
     ;; if target_device is boot device:
       ;; look for root partition device
       ;; get device index 
       ;; get active device slot 
       ;; 
     ;; else:
       ;; (autoboot/autoboot target-device)
       ;; (cond (= boot 2) slot-b
       ;;       (= boot 3) slot-a 


       (write-)
       {:boot  ""
        :slots {:a {:boot "/dev/sda" :root "/dev/sda"}}}


   (let [{boot-idx :boot try-boot-idx :try-boot}])

   (pack/validate! ujima-pack-path)

   (let [{:keys [root-device boot-device]} (disk/inactive-slot-info target-device)]
      
      (pack/unpack! ujima-pack-path boot-device root-device)
      
      (with-mounted! [boot-fs boot-device]
        (autoboot/cmdline! boot-fs root-device)))))



(defn ->deploy [env]
  (->RpiDeploy env))
