(ns lib.edn
  (:require [cheshire.core :as json]
            [clojure.string :as str])

  (:import  [java.io InputStream]))


(defn- snake->camel [k]
  (let [[x & xs] (str/split (name k) #"-")]
    (keyword
      (apply str x (map str/capitalize xs)))))


(defn- camel->snake [k]
  (keyword
    (-> (name k)
        (str/replace #"([a-z])([A-Z])" "$1-$2")
        (str/lower-case))))


(defn- throwable->map [err]
  (let [data (or (ex-data err) {})]
    {:type    (or (:type data) :error/unexpected)
     :message (or (ex-message err) "Task failed")
     :data    (dissoc data :type)}))


(defn- clj->json [value]
  (cond
    (instance? Throwable value) (throwable->map value)
    :otherwise                  value))


(defn- map-kv [key-fn value-fn value]
  (let [value (value-fn value)]
    (cond
      (map? value)    (into {}
                         (map (fn [[k v]]
                                [(if (keyword? k) (key-fn k) k)
                                 (map-kv key-fn value-fn v)])
                              value))

      (vector? value) (mapv #(map-kv key-fn value-fn %) value)
      (seq? value)    (map  #(map-kv key-fn value-fn %) value)
      :otherwise      value)))



(defn- stream? [thing]
  (instance? InputStream thing))


(defn json->edn [json-text]

  (as-> json-text $

    (cond
      (string? $) $            
      (stream? $) (slurp $)
      :otherwise  "{}")

    (json/parse-string $ true)
    (map-kv camel->snake identity $)

    (or $ {})))


(defn edn->json [data]
  (->> data
    (map-kv snake->camel clj->json)
    (json/generate-string)))
