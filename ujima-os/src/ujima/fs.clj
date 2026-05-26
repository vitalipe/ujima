(ns ujima.fs
  (:require [clojure.string  :as str]
            [clojure.java.io :as java-io]
            [clojure.edn     :as edn]
            [babashka.fs      :as fs]))


(defn slurp-text
  "Returns file text, or default on error. Does not throw."
  ([path]
   (slurp-text path ""))

  ([path default]
   (try
     (cond 
       (fs/exists? path) (slurp (str (java-io/file path)))
       :otherwise        default))))
     

(defn slurp-edn!
  "Reads EDN. Returns default on error, Does not throw."
  ([path]
   (slurp-edn! path nil))

  ([path default]
   (try
     (if-let [txt (slurp-text path nil)]            
       (try 
        (edn/read-string txt) 
        (catch Throwable _ default))

       ;; else 
       default))))
     
     
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


(defn probe-file! [root file-pattern]
  (let [[first-matching] (fs/glob root file-pattern)]
    (when first-matching
      (str first-matching))))


(defn file->number [path]
  (when (fs/exists? path)
    (-> path slurp str/trim parse-long))) 


(defn require-file! [path]
  (when-not (fs/regular-file? path)
    (throw
      (ex-info (str path " is not a regular file")
               {:path (str path)})))
  path)

