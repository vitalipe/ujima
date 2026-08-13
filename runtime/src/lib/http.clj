(ns lib.http
  "A small http-kit edge for ujima's backends (ujimad's machine edge today;
   the console at merge). Pure transport, zero domain requires and no state of
   its own — listen! is a port bind returning http-kit's stop fn, and the
   logger is injected (:log, a (fn [level message data]); println when the
   caller passes none).

   Callers pass :endpoints, a map of url prefix -> what that module serves:

     {\"api\" {:routes {\"GET /audio\" (fn [req] ...)} :errors {:audio/none 409}}
      \"\"    {:routes {\"GET /ui/state\" ...}         :errors {...}}}

   The prefix is the module's mount point, so a module never writes it into
   its own keys and two modules cannot collide. Routes are clj-simple-router
   masks; the whole lot compiles to ONE matcher, so the most specific route
   wins rather than whichever module was listed first.

   A handler is (fn [req]) and answers with data {:status n :body <edn>}, a raw
   ring response (files, streams — passed through untouched), or nil for a
   404. The request arrives parsed: :body is the decoded json (POST only, the
   raw stream is gone), :query is the query string as a map, and :format is
   the wire form the caller asked for. Data renders as json, ?format=edn for
   the edn wire (the console transport asks for it).

   Failures are ex-info {:error kw} mapped by the module's OWN :errors — a
   keyword one module knows is not a status for another module's throw. An
   unmapped keyword is a bug, not a status: it logs and 500s."
  (:require [clojure.string     :as str]
            [org.httpkit.server :as http]
            [clj-simple-router.core :as router]
            [lib.edn            :refer [edn->json json->edn]])
  (:import [java.net URLDecoder]))


;; transport-level vocabulary; every module gets it for free
(def ^:private base-errors {:request/malformed 400})

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
  "Parse once, here, so no handler parses it again. ?format is the edge's own
   and never reaches :query."
  [req]
  (let [query (decode-query (:query-string req))]
    (assoc req
           :format (if (= "edn" (:format query)) :edn :json)
           :query  (dissoc query :format)
           :body   (when (= :post (:request-method req)) (json->edn (:body req))))))


;; --- the response --------------------------------------------------------

(defn- render
  "A data :body picks its wire form; anything else (file, stream, http-kit's
   async channel) is already wire-shaped and passes through."
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
  "A module's routes answer with that module's error vocabulary. Anything it
   does not name is rethrown — an unmapped keyword is a bug, not a status."
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
    ;; a prefix keeps modules apart; two modules under ONE prefix can still
    ;; collide, and merge would drop one silently
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
  "The composed ring handler; listen! serves it, the unit test calls it
   directly."
  [{:keys [endpoints log]}]
  (let [log (or log println-log)]
    (partial handler {:route! (router/router (route-table endpoints log))
                      :log    log})))


(defn listen!
  "Bind host:port and serve; returns http-kit's stop fn. A taken port throws —
   the caller dies loudly and systemd rebuilds it."
  [{:keys [host port log] :as cfg :or {host "127.0.0.1" port 1337}}]
  ((or log println-log) :info "http listening" {:host host :port port})
  (http/run-server (app cfg) {:ip host :port port}))
