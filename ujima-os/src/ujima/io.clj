(ns ujima.io
  (:require [clojure.string  :as str]
            [clojure.java.io :as java-io]
            [clojure.edn :as edn]
            [babashka.process :as p]            
            [babashka.fs      :as fs]))
  

(defn sh!
  "Runs a command. Returns a result map. Does not throw."
  [cmd & args]
  (let [result (apply p/shell {:out :string
                               :err :string
                               :continue true}
                      (name cmd)
                      args)]

    {:ok?   (zero? (:exit result))
     :exit  (:exit result)
     :out   (str/trim (:out result))
     :err   (str/trim (:err result))}))


(defn sudo!
  [cmd & args]
  (apply sh! :sudo "-n" (name cmd) args))


(defn slurp-edn!
  "Reads EDN. Returns default on error, Does not throw."
  ([path]
   (slurp-edn! path nil))

  ([path default]
   (try
     
     (cond 
       (fs/exists? path) (edn/read-string (slurp (java-io/file path)))
       :otherwise        default)
     
     (catch Throwable _ default))))


(defn spit-file-atomic!
  "Writes text atomically. Returns a result map. Does not throw."
  [path text]
  (try
    (let [file (fs/path path)
          dir  (fs/parent file)
          tmp  (fs/path dir (str "." (fs/file-name file) ".tmp"))]

      (fs/create-dirs dir)

      (spit (str tmp) text)

      (fs/move tmp file {:replace-existing true :atomic-move true})

      {:ok? true
       :path path})

    (catch Throwable e
      {:ok? false
       :path path
       :error (ex-message e)
       :exception e})))


(defn probe-file! [root token-pattern]
  (let [[control-file] (fs/glob root token-pattern)]
    (when control-file
      (str control-file))))
