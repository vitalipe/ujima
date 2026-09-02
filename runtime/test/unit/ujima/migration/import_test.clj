(ns ujima.migration.import-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [ujima.control :as control]
            [ujima.migration.import :as import]
            [ujima.ujimactl :as ujimactl]
            [schema.ujima.settings :as defs]))


;; Same rig as control-test: real temp files, no stubs, no converge targets.


(defn- fresh! []
  (let [dir (str (fs/create-temp-dir))]
    (control/init! {:storage dir :tmp dir})
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
    (is (= 3 (:applied result)))
    (is (= [] (:dropped result)))
    (is (= "shule-3" (value [:system :name])))
    (is (= "Shule Circle" (value [:circle :name])))
    (let [raw (edn/read-string (slurp (str dir "/device.edn")))]
      (is (= defs/schema (:schema raw)) "written through control's writer, schema-stamped"))))


(deftest a-refused-entry-is-dropped-and-the-rest-applies
  ;; best effort: only the CALLER knows whether a refusal is a typo or the registry moving,
  ;; so this drops and reports, and the installer is the one that refuses on any drop
  (let [result (do (fresh!)
                   (import/import!
                     [{:scope :device :setting [:system :name] :value "ok-name"}
                      {:scope :device :setting [:not :a :setting]  :value 1}]
                     {}))]
    (is (= 1 (:applied result)))
    (is (= 1 (count (:dropped result))))
    (is (= {:scope :device :setting [:not :a :setting] :value 1}
           (:entry (first (:dropped result)))))
    (is (= "ok-name" (value [:system :name])) "the good one still landed")))


(deftest scope-not-allowed-is-reported-not-silently-pruned
  (fresh!)
  (let [result (import/import!
                 [{:scope :circle :setting [:system :name] :value "x"}]
                 {})]
    (is (zero? (:applied result)))
    (is (re-find #"cannot be set in scope" (-> result :dropped first :error)))))


(deftest shape-errors-speak-the-schema-language
  (fresh!)
  (let [result (import/import!
                 [{:scope :device :setting [:system :name] :value "no spaces!"}]
                 {})]
    (is (re-find #"name" (-> result :dropped first :error)))))


(deftest unknown-scope-is-reported
  (fresh!)
  (let [result (import/import!
                 [{:scope :nope :setting [:system :name] :value "x"}]
                 {})]
    (is (re-find #"unknown scope" (-> result :dropped first :error)))))


(deftest ephemeral-scopes-apply-but-warn
  (fresh!)
  (let [result (import/import!
                 [{:scope :session :setting [:keyboard :layout] :value "tz"}]
                 {})]
    (is (= 1 (:applied result)))
    (is (= 1 (count (:warnings result))))
    (is (re-find #"does not persist" (-> result :warnings first :warning)))
    (is (= "tz" (value [:keyboard :layout])))))


(deftest dry-run-touches-nothing
  ;; the same report, no write — what the installer inspects before it commits to seeding
  (let [dir (fresh!)
        result (import/import!
                 [{:scope :device :setting [:system :name] :value "shule-3"}
                  {:scope :device :setting [:system :gone] :value 1}]
                 {:dry-run true})]
    (is (zero? (:applied result)))
    (is (= 1 (count (:dropped result))))
    (is (not (fs/exists? (str dir "/device.edn"))))
    (is (unset? [:system :name]))))


(deftest an-empty-or-non-vector-input-applies-nothing
  (fresh!)
  (is (zero? (:applied (import/import! [] {}))))
  (is (seq   (:dropped (import/import! [] {}))))
  (is (zero? (:applied (import/import! {:scope :device} {})))))


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


(deftest import-into-a-running-machine-refuses-rather-than-writing-files
  ;; a direct write would land settings with no converge, so the machine would report the
  ;; new value while still behaving by the old
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not wired yet"
        (ujimactl/import! [{:scope :device :setting [:system :name] :value "x"}] {}))))
