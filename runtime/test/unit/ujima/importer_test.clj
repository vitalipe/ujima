(ns ujima.importer-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [ujima.control :as control]
            [ujima.importer :as importer]
            [schema.ujima.settings :as defs]))


;; Same rig as control-test: real temp files, no stubs, no converge targets.


(defn- fresh! []
  (let [dir (str (fs/create-temp-dir))]
    (control/init! {:storage dir :tmp dir :converge-targets []})
    dir))

(defn- value [key] (:effective (control/setting key)))


(deftest a-valid-file-applies-across-scopes
  (let [dir (fresh!)
        result (importer/import!
                 [{:scope :device :setting [:system :hostname] :value "shule-3"}
                  {:scope :device :setting [:system :timezone] :value "Africa/Nairobi"}
                  {:scope :circle :setting [:circle :name]     :value "Shule Circle"}]
                 {})]
    (is (:ok? result))
    (is (= 3 (:applied result)))
    (is (= "shule-3" (value [:system :hostname])))
    (is (= "Shule Circle" (value [:circle :name])))
    (let [raw (edn/read-string (slurp (str dir "/device.edn")))]
      (is (= defs/schema (:schema raw)) "written through control's writer, schema-stamped"))))


(deftest one-bad-entry-applies-nothing
  (let [dir (fresh!)
        result (importer/import!
                 [{:scope :device :setting [:system :hostname] :value "ok-name"}
                  {:scope :device :setting [:not :a :setting]  :value 1}]
                 {})]
    (is (not (:ok? result)))
    (is (= 0 (:applied result)))
    (is (= 1 (count (:errors result))))
    (is (not (fs/exists? (str dir "/device.edn"))) "all-or-nothing: no scope file written")
    (is (nil? (value [:system :hostname])))))


(deftest scope-not-allowed-is-an-error-not-a-silent-prune
  (fresh!)
  (let [result (importer/import!
                 [{:scope :circle :setting [:system :hostname] :value "x"}]
                 {})]
    (is (not (:ok? result)))
    (is (re-find #"cannot be set in scope" (-> result :errors first :error)))))


(deftest shape-errors-speak-the-schema-language
  (fresh!)
  (let [result (importer/import!
                 [{:scope :device :setting [:system :hostname] :value "no spaces!"}]
                 {})]
    (is (not (:ok? result)))
    (is (re-find #"hostname" (-> result :errors first :error)))))


(deftest unknown-scope-is-an-error
  (fresh!)
  (let [result (importer/import!
                 [{:scope :nope :setting [:system :hostname] :value "x"}]
                 {})]
    (is (not (:ok? result)))
    (is (re-find #"unknown scope" (-> result :errors first :error)))))


(deftest ephemeral-scopes-apply-but-warn
  (fresh!)
  (let [result (importer/import!
                 [{:scope :session :setting [:keyboard :layout] :value "tz"}]
                 {})]
    (is (:ok? result))
    (is (= 1 (count (:warnings result))))
    (is (re-find #"does not persist" (-> result :warnings first :warning)))
    (is (= "tz" (value [:keyboard :layout])))))


(deftest validate-only-touches-nothing
  (let [dir (fresh!)
        result (importer/import!
                 [{:scope :device :setting [:system :hostname] :value "shule-3"}]
                 {:validate-only true})]
    (is (:ok? result))
    (is (= 0 (:applied result)))
    (is (not (fs/exists? (str dir "/device.edn"))))
    (is (nil? (value [:system :hostname])))))


(deftest an-empty-or-non-vector-file-is-an-error
  (fresh!)
  (is (not (:ok? (importer/import! [] {}))))
  (is (not (:ok? (importer/import! {:scope :device} {})))))
