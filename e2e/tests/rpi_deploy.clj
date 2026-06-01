(ns e2e.tests.rpi-deploy
  (:require [babashka.fs :as fs] 

            [ujima.linux.shell     :refer [$! sudo$! require-root!]] 
            [ujima.linux.disk.loop :as    loopback]))



(defn test! [name f]
  (try

    (print (str "TEST " name " ... "))
    (flush)

    (let [result (f)]
      (if (zero? (:exit result))
        (do 
          (println "OK")
          true)
        
        (do
          (println "FAIL")
          (println " " (:err result))
          false)))
   
    (catch Throwable e
      (println "FAIL")
      (println " " (ex-message e))
      false)))


(defn run! [env]
  (let [img-file (fs/path (:tmp env) "test-disk.img")]

    (require-root!)

    ($! truncate -s "32G" [img-file])
    
    (loop/with-loopback-device [fs img-file]
      
      (test! "0. initial state"   #())
      (test! "1. initialize disk" #())      
      (test! "1. install slot a"  #())
      (test! "2. install slot b"  #()))))




(comment

 ;; pretest 
 1 create img file
 2 loop attach
 3 create 2 ujima packs

   1 (ujima-boot-info) = nil
   2 (install-ujima! loop-device) 
   3 (ujima-boot-info)
   4 (upgrade-ujima! loop-device)

 4 detach loop
 5 cleanup files)

    