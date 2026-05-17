(ns ujima.io.fs
  (:require [clojure.string  :as str]
            [clojure.java.io :as java-io]
            [clojure.edn     :as edn]
            
            [babashka.fs      :as fs]
            [ujima.io.shell   :refer [sh]]))
  

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


(defn probe-file! [root file-pattern]
  (let [[first-matching] (fs/glob root file-pattern)]
    (when first-matching
      (str first-matching))))


(defn require-file! [path]
  (when-not (fs/regular-file? path)
    (throw
      (ex-info (str path " is not a regular file")
               {:path (str path)})))
  path)


(defn require-block-device! [path]
  (let [{:keys [ok?]} (sh :test "-b" (str path))]
    (when-not ok?
      (throw
        (ex-info (str path " is not a block device")
                 {:path (str path)}))))
  path)