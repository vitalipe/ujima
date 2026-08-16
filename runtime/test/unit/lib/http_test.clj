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


;; --- the raw body and the signing hook -----------------------------------

(def ^:private raw-echo
  (http/app
    {:endpoints {"api" {:routes {"POST /raw-echo" (fn [req] {:status 200 :body {:raw  (:raw-body req)
                                                                                :body (:body req)}})
                                 "GET  /raw-echo" (fn [req] {:status 200 :body {:raw (:raw-body req)}})}}}
     :log (fn [_ _ _])}))


(deftest the-raw-body-is-kept-as-it-arrived
  (let [sent "{\"appId\": \"calc\"}"
        seen (json->edn (:body (raw-echo {:request-method :post :uri "/api/raw-echo" :body sent})))]
    (is (= sent (:raw seen)) "byte-identical — a signature covers these, not the reparse")
    (is (= {:app-id "calc"} (:body seen)) "and :body is still parsed, from the string"))

  (is (= "" (:raw (json->edn (:body (raw-echo {:request-method :get :uri "/api/raw-echo"})))))
      "no body is an empty string, not nil — one shape to hash"))


(defn- signing-app [& {:keys [body]}]
  (http/app
    {:endpoints {"api" {:routes {"GET /thing" (fn [_] {:status 201 :body (or body {:ok true})})}}}
     :log  (fn [_ _ _])
     :sign (fn [req resp]
             {"response-signature" (str (:uri req) "|" (:status resp) "|" (:body resp))})}))


(deftest a-listener-with-sign-signs-every-response
  (let [resp ((signing-app) {:request-method :get :uri "/api/thing"})]
    (is (= "/api/thing|201|{\"ok\":true}" (get-in resp [:headers "response-signature"]))
        "the hook sees the RENDERED body and the status, and owns the format")
    (is (= "application/json" (get-in resp [:headers "content-type"]))
        "and the render's own headers survive the merge"))

  (is (= "/api/nope|404|{\"error\":\"not found\"}"
         (get-in ((signing-app) {:request-method :get :uri "/api/nope"}) [:headers "response-signature"]))
      "the edge's own errors are signed too — an unsigned 404 would look tampered"))


(deftest a-listener-without-sign-signs-nothing
  (is (nil? (get-in (GET "/api/echo/x") [:headers "response-signature"]))))


(deftest a-signed-endpoint-refuses-to-serve-an-unsignable-body
  ;; render passes streams through unread, so signing one would cover nothing
  (let [app  (signing-app :body (java.io.ByteArrayInputStream. (.getBytes "a file")))
        resp (app {:request-method :get :uri "/api/thing"})]
    (is (= 500 (:status resp)) "loud, not a response carrying a signature over nothing")
    (is (= {:error "internal error"} (json->edn (:body resp))))
    (is (some? (get-in resp [:headers "response-signature"])) "and the 500 itself is still signed")))
