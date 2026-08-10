(ns lib.http-test
  "The edge's own mechanics, against fake tier handlers — no domain: the
   first-response-wins try, the POST json edge, the data render (json default,
   ?format=edn), and ex-info -> status mapping. The tier tables live in
   their tiers and are exercised by their consumers."
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn  :as edn]
            [lib.edn      :refer [json->edn]]
            [lib.http     :as http]))


(def ^:private app
  (http/app
    {:handlers [(fn [req parts body]
                  (case (first parts)
                    "echo" {:status 200 :body {:parts parts :req-body body}}
                    "raw"  {:status 200 :body "already wire-shaped"}
                    "no"   (throw (ex-info "tier said no" {:error :tier/rejected}))
                    "bad"  (throw (ex-info "bad input" {:error :request/malformed}))
                    nil))
                (fn [_req parts _body]
                  (case (first parts)
                    "second" {:status 200 :body {:tier :second}}
                    "bug"    (throw (ex-info "oops" {:error :tier/unmapped}))
                    nil))]
     :errors {:tier/rejected 409}
     :log    (fn [_level _message _data])}))

(defn- GET  [uri & [qs]]   (app {:request-method :get :uri uri :query-string qs}))
(defn- POST [uri body]     (app {:request-method :post :uri uri :body body}))
(defn- read-body [resp]    (json->edn (:body resp)))


(deftest first-response-wins
  (is (= ["echo" "a" "b"] (:parts (read-body (GET "/echo/a/b")))))
  (is (= ["echo"]         (:parts (read-body (GET "/echo/")))) "trailing slashes are fine")
  (is (= {:tier "second"} (read-body (GET "/second/x"))) "a request the first tier passes on falls through")
  (is (= 404 (:status (GET "/nope"))) "no tier answered")
  (is (= 404 (:status (GET "/")))))


(deftest renders-data-json-by-default-edn-on-request
  (is (= "application/json" (get-in (GET "/echo") [:headers "content-type"])))
  (is (= {:error "not found"} (read-body (GET "/nope"))) "edge's own errors render too")
  (let [resp (GET "/echo/x" "format=edn")]
    (is (= "application/edn" (get-in resp [:headers "content-type"])))
    (is (= ["echo" "x"] (:parts (edn/read-string (:body resp)))) "keywords survive the edn wire"))
  (let [resp (GET "/raw")]
    (is (= "already wire-shaped" (:body resp)) "a non-coll body passes through untouched")
    (is (nil? (:headers resp)))))


(deftest parses-the-json-body-for-posts-only
  (is (= {:app-id "calc"} (:req-body (read-body (POST "/echo" "{\"appId\": \"calc\"}")))) "keys kebab back")
  (is (nil? (:req-body (read-body (GET "/echo"))))))


(deftest maps-ex-info-to-statuses
  (is (= 409 (:status (GET "/no")))  "a tier's own error vocabulary, merged at init")
  (is (= 400 (:status (GET "/bad"))) ":request/malformed is transport-level")
  (is (= 500 (:status (GET "/bug"))) "an unmapped error keyword is a real bug"))
