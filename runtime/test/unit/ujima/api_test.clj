(ns ujima.api-test
  "The frozen v1 shapes against what the tier answers."
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs  :as fs]
            [malli.core   :as m]
            [malli.error  :as me]
            [lib.http     :as http]
            [ujima.control    :as control]
            [ujima.api        :as api]
            [schema.ujima.api.query :as query]))


(defn- fresh! []
  (let [dir (str (fs/create-temp-dir))]
    (control/init! {:storage dir :tmp dir :converge-targets []})))

(defn- GET [uri]
  (let [app (http/app {:endpoints {"api" api/endpoints} :log (fn [& _])})]
    (read-string (:body (app {:request-method :get :uri uri :query-string "format=edn"})))))

(defn- drift [shape v] (some->> (m/explain shape v) me/humanize))


(deftest the-machine-tree-answers-its-contract
  (fresh!)
  (is (nil? (drift query/machine (GET "/api/query/machine")))
      "every node together has to make the shape the contract promises"))


(deftest every-settings-leaf-is-a-record
  (fresh!)
  (let [tree (GET "/api/query/settings")
        recs (map #(get-in tree %) (keys (control/settings)))]
    (is (= (count (control/settings)) (count recs)) "one leaf per setting")
    (is (nil? (first (keep (partial drift query/settings-record) recs))))))


(deftest a-slice-of-the-machine-tree-is-the-shape-a-write-reports
  (fresh!)
  (is (nil? (drift query/audio (GET "/api/query/machine/audio")))
      "audio is one def, so the two can't drift apart"))


(deftest every-verb-is-answerable
  (doseq [[path {:keys [doc handler]}] api/commands]
    (is (string? doc)  (str path " has no :doc"))
    (is (fn? handler)  (str path " has no :handler"))))
