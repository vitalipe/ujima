(ns ujima.edn
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import '[java.io InputStream]))


(defn snake->camel [k]
  (let [[x & xs] (str/split (name k) #"-")]
    (keyword
      (apply str x (map str/capitalize xs)))))


(defn camel->snake [k]
  (keyword
    (str/replace (name k) #"([a-z])([A-Z])" "$1-$2")))


(defn convert-keys [f x]
  (cond
    (map? x)  (into {}
                (map (fn [[k v]]
                       [(if (keyword? k) (f k) k)
                        (convert-keys f v)])
                     x))

   (vector? x) (mapv #(convert-keys f %) x)
   (seq? x)    (map #(convert-keys f %) x)
   :otherwise  x))


(defn stream? [thing]
  (instance? InputStream thing))


(defn json->edn [json-text]

  (as-> json-text $

    (cond
      (string? $) $            
      (stream? $) (slurp $)
      :otherwise  "{}")

    (json/parse-string $ true)
    (convert-keys camel->snake $)

    (or $ {})))


(defn edn->json [value]
  (json/generate-string (convert-keys snake->camel value)))



