(ns ujima.migration.import-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [ujima.control :as control]
            [ujima.migration.import :as import]
            [schema.ujima.settings :as defs]))


;; Same rig as control-test: real temp files, no stubs, no converge targets.


(defn- fresh! []
  (let [dir (str (fs/create-temp-dir))]
    (control/init! {:storage dir :tmp dir :converge-targets []})
    dir))

(defn- value [key] (:effective (control/setting key)))

;; :default means no scope holds this setting — "nothing was applied" said precisely.
;; `value` cannot say it: an unset setting reads its DEFAULT, never nil.
(defn- unset? [key] (= :default (:via (control/setting key))))


(deftest a-valid-file-applies-across-scopes
  (let [dir (fresh!)
        result (import/import!
                 [{:scope :device :setting [:system :name] :value "shule-3"}
                  {:scope :device :setting [:system :timezone] :value "Africa/Nairobi"}
                  {:scope :circle :setting [:circle :name]     :value "Shule Circle"}]
                 {})]
    (is (:ok? result))
    (is (= 3 (:applied result)))
    (is (= "shule-3" (value [:system :name])))
    (is (= "Shule Circle" (value [:circle :name])))
    (let [raw (edn/read-string (slurp (str dir "/device.edn")))]
      (is (= defs/schema (:schema raw)) "written through control's writer, schema-stamped"))))


(deftest one-bad-entry-applies-nothing
  (let [dir (fresh!)
        result (import/import!
                 [{:scope :device :setting [:system :name] :value "ok-name"}
                  {:scope :device :setting [:not :a :setting]  :value 1}]
                 {})]
    (is (not (:ok? result)))
    (is (= 0 (:applied result)))
    (is (= 1 (count (:errors result))))
    (is (not (fs/exists? (str dir "/device.edn"))) "all-or-nothing: no scope file written")
    (is (unset? [:system :name]))))


(deftest scope-not-allowed-is-an-error-not-a-silent-prune
  (fresh!)
  (let [result (import/import!
                 [{:scope :circle :setting [:system :name] :value "x"}]
                 {})]
    (is (not (:ok? result)))
    (is (re-find #"cannot be set in scope" (-> result :errors first :error)))))


(deftest shape-errors-speak-the-schema-language
  (fresh!)
  (let [result (import/import!
                 [{:scope :device :setting [:system :name] :value "no spaces!"}]
                 {})]
    (is (not (:ok? result)))
    (is (re-find #"name" (-> result :errors first :error)))))


(deftest unknown-scope-is-an-error
  (fresh!)
  (let [result (import/import!
                 [{:scope :nope :setting [:system :name] :value "x"}]
                 {})]
    (is (not (:ok? result)))
    (is (re-find #"unknown scope" (-> result :errors first :error)))))


(deftest ephemeral-scopes-apply-but-warn
  (fresh!)
  (let [result (import/import!
                 [{:scope :session :setting [:keyboard :layout] :value "tz"}]
                 {})]
    (is (:ok? result))
    (is (= 1 (count (:warnings result))))
    (is (re-find #"does not persist" (-> result :warnings first :warning)))
    (is (= "tz" (value [:keyboard :layout])))))


(deftest validate-only-touches-nothing
  (let [dir (fresh!)
        result (import/import!
                 [{:scope :device :setting [:system :name] :value "shule-3"}]
                 {:validate-only true})]
    (is (:ok? result))
    (is (= 0 (:applied result)))
    (is (not (fs/exists? (str dir "/device.edn"))))
    (is (unset? [:system :name]))))


(deftest an-empty-or-non-vector-file-is-an-error
  (fresh!)
  (is (not (:ok? (import/import! [] {}))))
  (is (not (:ok? (import/import! {:scope :device} {})))))


(deftest validate-is-pure
  ;; must answer without control/init! — the upgrade asks before standing the slot up
  (let [{:keys [errors]} (import/validate
                           [{:scope :device :setting [:not :a :setting] :value 1}])]
    (is (= 1 (count errors)))
    (is (re-find #"unknown setting" (:error (first errors))))))


(deftest a-renamed-setting-is-refused-by-name
  ;; the target names what it will not take, so a migration drops those and carries the rest
  (let [{:keys [errors]} (import/validate
                           [{:scope :device :setting [:system :name]  :value "keep-me"}
                            {:scope :device :setting [:system :gone]  :value "drop-me"}])]
    (is (= 1 (count errors)))
    (is (= {:scope :device :setting [:system :gone] :value "drop-me"}
           (:entry (first errors))))))
