(ns e2e.tests.cli
  (:require [babashka.process :as p]))


(defn run-cmd! [& args]
  (apply p/shell
    {:out :string
     :err :string
     :continue true
     :dir "."}
    
    "bb" "cli" "runtime" args))


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


(defn run! [_ctx]
  (every? true?
    [(test! "hostname" #(run-cmd! "hostname"))
     (test! "timezone" #(run-cmd! "timezone"))

     (test! "keyboard layouts" #(run-cmd! "keyboard-layouts"))

     (test! "volume get"    #(run-cmd! "volume"))
     (test! "volume set"    #(run-cmd! "volume" "60"))
     (test! "control token" #(run-cmd! "control-token"))]))
