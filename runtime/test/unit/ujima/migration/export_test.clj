(ns ujima.migration.export-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [ujima.control :as control]
            [ujima.migration.export :as export]
            [ujima.migration.import :as import]))


(defn- fresh! []
  (let [dir (str (fs/create-temp-dir))]
    (control/init! {:storage dir :tmp dir})
    dir))


(deftest emits-only-what-a-scope-actually-holds
  (fresh!)
  (is (= [] (export/export)) "a machine with nothing set exports nothing — defaults are not settings")

  (control/update-settings! :device #(assoc % [:system :name] "shule-3"))
  (is (= [{:scope :device :setting [:system :name] :value "shule-3"}]
         (export/export))))


(deftest round-trips-through-import
  ;; the contract between two versions: whatever one exports, the other can apply
  (fresh!)
  (control/update-settings! :device #(assoc % [:system :name] "shule-3"))
  (control/update-settings! :circle #(assoc % [:circle :name] "Shule Circle"))

  (let [exported (export/export)]
    (fresh!)                                              ; a different machine
    (is (= (count exported) (:applied (import/import! exported {}))))
    (is (= exported (export/export)))))


(deftest carries-secrets
  ;; the psk is what makes an upgraded slot reachable — export is not the public projection
  (fresh!)
  (control/update-settings! :circle #(assoc % [:network :wifi :psk] "1337hax0rIOT"))
  (is (= [{:scope :circle :setting [:network :wifi :psk] :value "1337hax0rIOT"}]
         (export/export))))


(deftest skips-scopes-that-do-not-persist
  ;; :session is gone at the next boot — drop it at the source, not at the target
  (fresh!)
  (control/update-settings! :session #(assoc % [:keyboard :layout] "tz"))
  (control/update-settings! :device  #(assoc % [:system :name] "shule-3"))
  (is (= [{:scope :device :setting [:system :name] :value "shule-3"}]
         (export/export))))


(deftest is-ordered
  ;; a stable order keeps a diff of two exports readable
  (fresh!)
  (control/update-settings! :device #(assoc % [:system :name] "shule-3"))
  (control/update-settings! :circle #(assoc % [:circle :name] "Shule Circle"))
  (is (= [:circle :device] (mapv :scope (export/export)))))
