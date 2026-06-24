(ns lib.io
  (:require [clojure.string  :as str]
            [clojure.edn     :as edn]
            [babashka.fs      :as fs]
            [lib.util         :refer [deep-merge]]))


(defn slurp-text
  "Returns file text, or default on error. Does not throw."
  ([path]
   (slurp-text path ""))

  ([path default]
   (try
     (cond 
       (fs/exists? path) (slurp (str path))
       :otherwise        default)
     (catch Exception _ default))))


(defn slurp-edn
  "Reads EDN. Returns default on error, Does not throw."
  ([path]
   (slurp-edn path nil))

  ([path default]
   (try
     (if-let [txt (slurp-text path nil)]            
       (try 
        (edn/read-string txt) 
        (catch Exception _ default))

       ;; else 
       default))))
     

(defn spit-edn! [path content]
  (spit (str path)
        (pr-str content)))


(defn spit-file-atomic!
  "Writes text atomically. Returns the written content. Throws on error"
  [path text]
  (try
    (let [file (fs/path path)
          dir  (fs/parent file)
          tmp  (fs/path dir (str "." (fs/file-name file) ".tmp"))]

      (fs/create-dirs dir)
      (spit (str tmp) text)
      (fs/move tmp file {:replace-existing true :atomic-move true})

      (slurp-text path))))


(defn probe-file! [root file-pattern]
  (let [[first-matching] (fs/glob root file-pattern)]
    (when first-matching
      (str first-matching))))


(defn file->number [path]
  (when (fs/exists? path)
    (-> path str slurp str/trim parse-long)))


(defn file->uint-be
  "Read `path`'s bytes as a big-endian unsigned integer. Missing file ⇒ 0."
  [path]
  (if-not (fs/exists? path)
    0
    (->> (fs/read-all-bytes path)
         (reduce (fn [a b] (+ (* a 256) (bit-and b 0xff))) 0))))


(defn slurp-config
  "Read config `name` from `config-dir`, deep-merging
   <name>.edn < <name>.local.edn < <name>.dev.edn (later wins;
   missing optional files are skipped)."
  [config-dir name]
  (deep-merge
    (slurp-edn (fs/path config-dir (str name ".edn")))
    (slurp-edn (fs/path config-dir (str name ".local.edn")))
    (slurp-edn (fs/path config-dir (str name ".dev.edn")))))
