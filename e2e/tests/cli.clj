(ns e2e.tests.cli
  (:require [babashka.process :as p]))


(defn env->env-file-path [env]
  (get-in env [:args 0] "assets/e2e/ujima.edn"))
 

(defn run-cmd! [env-path & args]
  (apply p/shell
    {:out :string
     :err :string
     :continue true}
    "bb" "-m" "ujima.cli" env-path args))


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
  (let [env-path (env->env-file-path env)]

    (every? true?
      [(test! "hostname"
         #(run-cmd! env-path "hostname"))

       (test! "timezone"
         #(run-cmd! env-path "timezone"))

       (test! "keyboard layouts"
         #(run-cmd! env-path "keyboard-layouts"))

       (test! "volume get"
         #(run-cmd! env-path "volume"))

       (test! "volume set"
         #(run-cmd! env-path "volume" "60"))

       (test! "control token"
         #(run-cmd! env-path "control-token"))])))
