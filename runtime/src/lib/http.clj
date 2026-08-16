(ns lib.http
  "http-kit edge. :endpoints is {prefix -> {:routes {\"GET /x\" (fn [req])} :errors {kw status}}},
   compiled to one router. A handler answers {:status :body}, a raw ring response, or nil for a
   404; the request arrives parsed (:body :query :format). An unnamed error keyword is a 500."
  (:require [clojure.string     :as str]
            [org.httpkit.server :as http]
            [clj-simple-router.core :as router]
            [lib.edn            :refer [edn->json json->edn]])
  (:import [java.net URLDecoder]))


;; transport-level vocabulary; every module gets it for free
(def ^:private base-errors {:request/malformed 400})

;; DELETE's body has no settled meaning — left out on purpose
(def ^:private body-methods #{:post :put :patch})

(defn- println-log [level message data]
  (println (str "[" (name level) "] " message) data))


;; --- the request ---------------------------------------------------------

(defn- decode-query [query-string]
  (into {}
        (for [pair  (str/split (or query-string "") #"&")
              :when (seq pair)
              :let  [[k v] (str/split pair #"=" 2)]]
          [(keyword (URLDecoder/decode k "UTF-8"))
           (URLDecoder/decode (or v "") "UTF-8")])))

(defn- prepare
  "Parse once. ?format is the edge's own and never reaches :query."
  [req]
  (let [query (decode-query (:query-string req))]
    (assoc req
           :format (if (= "edn" (:format query)) :edn :json)
           :query  (dissoc query :format)
           :body   (when (body-methods (:request-method req)) (json->edn (:body req))))))


;; --- the response --------------------------------------------------------

(defn- render
  "A data :body picks its wire form; a file, stream or async channel is
   already wire-shaped and passes through."
  [fmt {:keys [body] :as resp}]
  (if (coll? body)
    (if (= :edn fmt)
      (-> resp
          (assoc :body (str (pr-str body) "\n"))
          (assoc-in [:headers "content-type"] "application/edn"))
      (-> resp
          (assoc :body (edn->json body))
          (assoc-in [:headers "content-type"] "application/json")))
    resp))


;; --- routing -------------------------------------------------------------

(defn- mount [prefix route]
  (if (str/blank? prefix)
    route
    (str/replace-first route #"^(\S+)\s+/?" (str "$1 /" prefix "/"))))

(defn- guard
  "Map a throw against this module's vocabulary; rethrow what it never named."
  [errors log f]
  (fn [req]
    (try
      (f req)
      (catch clojure.lang.ExceptionInfo e
        (if-let [status (get errors (:error (ex-data e)))]
          (do (log :warn "http: rejected" {:uri (:uri req) :error (ex-message e)})
              {:status status :body {:error (ex-message e)}})
          (throw e))))))

(defn- route-table [endpoints log]
  (let [table (into {}
                    (for [[prefix {:keys [routes errors]}] endpoints
                          [route f] routes]
                      [(mount prefix route) (guard (merge base-errors errors) log f)]))]
    ;; two modules on one listener can claim the same path; merge drops one
    (assert (= (count table) (reduce + (map (comp count :routes val) endpoints)))
            "duplicate route across endpoints")
    table))


(defn- handler [{:keys [route! log]} req]
  (let [req (prepare req)]
    (render (:format req)
      (try
        (or (route! req) {:status 404 :body {:error "not found"}})
        (catch Throwable e
          (log :error "http: handler failed" {:uri (:uri req) :error (ex-message e)})
          {:status 500 :body {:error "internal error"}})))))


(defn app
  "The composed ring handler; listen! serves it, the test calls it directly."
  [{:keys [endpoints log]}]
  (let [log (or log println-log)]
    (partial handler {:route! (router/router (route-table endpoints log))
                      :log    log})))


(defn listen!
  "Bind host:port and serve; returns http-kit's stop fn. A taken port throws —
   the caller dies loudly and systemd rebuilds it. :host and :port are required:
   a default would let two listeners silently collide on it."
  [{:keys [host port log] :as cfg}]
  (assert (and host port) (str "listen! needs :host and :port, got " {:host host :port port}))
  ((or log println-log) :info "http listening" {:host host :port port})
  (http/run-server (app cfg) {:ip host :port port}))
