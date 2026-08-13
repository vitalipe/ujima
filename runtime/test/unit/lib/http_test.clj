(ns lib.http-test
  "The edge's own mechanics, against fake endpoint modules — no domain: the
   prefix mount, the parsed request (json body, query map, ?format), the data
   render, and ex-info -> status against the module's OWN vocabulary. The real
   route tables live in their modules and are exercised by their consumers."
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn  :as edn]
            [lib.edn      :refer [json->edn]]
            [lib.http     :as http]))


(def ^:private app
  (http/app
    {:endpoints
     {"api" {:errors {:tier/rejected 409}
             :routes {"GET  /echo/*"  (fn [{[x] :path-params :as req}]
                                        {:status 200 :body {:seen x
                                                            :req-body (:body req)
                                                            :query (:query req)}})
                      "POST /echo"    (fn [req] {:status 200 :body {:req-body (:body req)}})
                      "GET  /raw"     (fn [_] {:status 200 :body "already wire-shaped"})
                      "GET  /no"      (fn [_] (throw (ex-info "tier said no" {:error :tier/rejected})))
                      "GET  /bad"     (fn [_] (throw (ex-info "bad input" {:error :request/malformed})))
                      "GET  /bug"     (fn [_] (throw (ex-info "oops" {:error :tier/unmapped})))}}

      ""    {:errors {:tier/unmapped 418}
             :routes {"GET /root"  (fn [_] {:status 200 :body {:module :root}})
                      "GET /decl"  (fn [_] (throw (ex-info "mine" {:error :tier/unmapped})))}}}
     :log (fn [_level _message _data])}))

(defn- GET  [uri & [qs]] (app {:request-method :get :uri uri :query-string qs}))
(defn- POST [uri body]   (app {:request-method :post :uri uri :body body}))
(defn- read-body [resp]  (json->edn (:body resp)))


(deftest a-prefix-is-the-modules-mount-point
  (is (= "a" (:seen (read-body (GET "/api/echo/a")))) "the module never writes its own prefix")
  (is (= {:module "root"} (read-body (GET "/root"))) "an empty prefix mounts at the root")
  (is (= 404 (:status (GET "/echo/a"))) "unmounted path")
  (is (= 404 (:status (GET "/nope"))))
  (is (= 404 (:status (GET "/")))))


(deftest the-request-arrives-parsed
  (is (= {:app-id "calc"} (:req-body (read-body (POST "/api/echo" "{\"appId\": \"calc\"}"))))
      "json body, keys kebabbed")
  (is (nil? (:req-body (read-body (GET "/api/echo/x")))) "bodies are a POST thing")
  (is (= {:a "1" :b "x"} (:query (read-body (GET "/api/echo/x" "a=1&b=x"))))
      "the query string as a map")
  (is (= {:a "1"} (:query (read-body (GET "/api/echo/x" "a=1&format=json"))))
      "?format is the edge's own and never reaches a handler"))


(deftest renders-data-json-by-default-edn-on-request
  (is (= "application/json" (get-in (GET "/api/echo/x") [:headers "content-type"])))
  (is (= {:error "not found"} (read-body (GET "/nope"))) "edge's own errors render too")
  (let [resp (GET "/api/echo/x" "format=edn")]
    (is (= "application/edn" (get-in resp [:headers "content-type"])))
    (is (= "x" (:seen (edn/read-string (:body resp)))) "keywords survive the edn wire"))
  (let [resp (GET "/api/raw")]
    (is (= "already wire-shaped" (:body resp)) "a non-coll body passes through untouched")
    (is (nil? (:headers resp)))))


(deftest each-module-maps-its-own-errors
  (is (= 409 (:status (GET "/api/no")))  "the module's own vocabulary")
  (is (= 400 (:status (GET "/api/bad"))) ":request/malformed is transport-level, everyone gets it")
  (is (= 418 (:status (GET "/decl")))    "the root module maps :tier/unmapped")
  (is (= 500 (:status (GET "/api/bug")))
      "and the SAME keyword is a bug in a module that never declared it"))


(deftest a-route-two-modules-both-claim-dies-at-build
  ;; distinct prefixes are not enough: a root-mounted module can declare a path
  ;; that another module's prefix already owns, and merge would drop one
  (is (thrown? AssertionError
        (http/app {:endpoints {"api" {:routes {"GET /x"     (fn [_] nil)}}
                               ""    {:routes {"GET /api/x" (fn [_] nil)}}}}))))
