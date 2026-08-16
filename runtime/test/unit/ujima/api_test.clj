(ns ujima.api-test
  "The frozen v1 shapes against what the tier answers."
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs  :as fs]
            [malli.core   :as m]
            [malli.error  :as me]
            [lib.edn      :refer [edn->json]]
            [lib.http     :as http]
            [ujima.control      :as control]
            [ujima.linux.system :as system]
            [ujima.api          :as api]
            [schema.ujima.api.query :as query]))


(defn- fresh! []
  (let [dir (str (fs/create-temp-dir))]
    (control/init! {:storage dir :tmp dir :converge-targets []})))

(defn- GET
  ([uri] (GET uri {}))
  ([uri cfg]
   (let [app (http/app {:endpoints {"api" (api/endpoints cfg)} :log (fn [& _])})]
     (read-string (:body (app {:request-method :get :uri uri :query-string "format=edn"}))))))

(defn- POST
  ([uri] (POST uri nil))
  ([uri body]
   (let [app  (http/app {:endpoints {"api" (api/endpoints {})} :log (fn [& _])})
         resp (app (cond-> {:request-method :post :uri uri :query-string "format=edn"}
                     body (assoc :body (edn->json body))))]
     {:status (:status resp) :body (some-> (:body resp) read-string)})))

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


(deftest the-version-is-the-deploy-stamp
  (fresh!)
  (is (= {:version "v9.9-test"} (GET "/api/query/machine/image" {:version "v9.9-test"}))
      "cfg :version (config/env.edn on device) answers the image node")
  (is (= {:version nil} (GET "/api/query/machine/image"))
      "no stamp = an honest nil, not a fake"))


(deftest the-disk-node-merges-boot-info-and-live-space
  (fresh!)
  (is (= {:type :ab :slot :a :storage nil :settings nil}
         (GET "/api/query/machine/disk" {:disk {:type :ab :boot-slot :a
                                                :storage "/dev/nope1" :config "/dev/nope2"}}))
      "boot-time disk info + live space lookup; absent devices read nil")
  (is (= {:type nil :slot nil :storage nil :settings nil} (GET "/api/query/machine/disk"))
      "no ujima disk (a host run) = the shape with honest nils"))


(deftest the-id-is-the-disk-stamp
  (fresh!)
  (is (= {:id "1b0c-test"} (GET "/api/query/machine/id" {:id "1b0c-test"}))
      "cfg :id (system-disk-id! at startup) answers the id leaf")
  (is (= {:id nil} (GET "/api/query/machine/id"))
      "no stamped system = an honest nil (a host run)"))


(deftest a-cleared-setting-releases-the-hold
  (fresh!)
  (is (= 202 (:status (POST "/api/commands/settings/audio/muted" {:scope "activity" :value true}))))
  (is (= {:effective true :via :activity}
         (select-keys (GET "/api/query/settings/audio/muted") [:effective :via])))
  (is (= 202 (:status (POST "/api/commands/clear/activity/audio/muted"))))
  (let [rec (GET "/api/query/settings/audio/muted")]
    (is (= {:effective false :via :default} (select-keys rec [:effective :via])))
    (is (nil? (get-in rec [:scopes :activity])) "released — the entry is gone, not false")))


(deftest a-scope-clear-releases-everything-it-holds
  (fresh!)
  (POST "/api/commands/settings/audio/muted"     {:scope "session"  :value true})
  (POST "/api/commands/settings/audio/muted"     {:scope "activity" :value false})
  (POST "/api/commands/settings/keyboard/layout" {:scope "activity" :value "us"})
  (is (= {:effective false :via :activity}
         (select-keys (GET "/api/query/settings/audio/muted") [:effective :via]))
      "the activity hold shadows the session write")
  (is (= 202 (:status (POST "/api/commands/clear/activity/"))))
  (is (= {:effective true :via :session}
         (select-keys (GET "/api/query/settings/audio/muted") [:effective :via]))
      "the kid's own session state resumes")
  (is (= :default (:via (GET "/api/query/settings/keyboard/layout")))))


(deftest the-clock-verb-sets-and-records-the-floor
  (fresh!)
  (let [set-to (atom nil)]
    (with-redefs [system/clock! (fn [ms] (reset! set-to ms))]
      (is (= 202 (:status (POST "/api/commands/system/clock" {:epoch 1755264000000}))))
      (is (= 1755264000000 @set-to) "the wall clock was set to the instant")
      (is (= {:effective 1755264000000 :via :device}
             (select-keys (GET "/api/query/settings/system/clock/epoch-floor") [:effective :via]))
          "the assertion became the new floor")
      (is (= 400 (:status (POST "/api/commands/system/clock" {:epoch -5}))))
      (is (= 400 (:status (POST "/api/commands/system/clock" {})))))))


(deftest clear-rejects-what-it-must
  (fresh!)
  (is (= 404 (:status (POST "/api/commands/clear/banana/audio/muted"))) "unknown scope is not an address")
  (is (= 404 (:status (POST "/api/commands/clear/activity/no/such"))) "unknown setting")
  (is (= 404 (:status (POST "/api/commands/clear/device/audio/muted"))) "device is not a clear address")
  (is (= 404 (:status (POST "/api/commands/clear/device/"))) "runtime scopes only — device falls through"))


(deftest every-verb-is-answerable
  (doseq [[path {:keys [doc handler]}] api/commands]
    (is (string? doc)  (str path " has no :doc"))
    (is (fn? handler)  (str path " has no :handler"))))
