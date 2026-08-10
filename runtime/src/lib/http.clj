(ns lib.http
  "A small http-kit edge for ujima's backends (ujimad's machine edge today;
   the console at merge). Pure transport, zero domain requires and no state
   of its own — listen! is a port bind returning http-kit's stop fn, and the
   logger is injected (:log, a (fn [level message data]); println when the
   caller passes none). Dispatch is a try of the tier handlers the caller
   passes in — a tier handler is (fn [req parts body]) and answers with data
   {:status n :body <edn>}, a raw ring response (files, streams — passed
   through untouched), or nil when the request isn't its; first response
   wins. Data renders as json, ?format=edn for the edn wire (the console
   transport asks for it); POST request bodies are json until the commands
   family brings edn."
  (:require [clojure.string     :as str]
            [org.httpkit.server :as http]
            [lib.edn            :refer [edn->json json->edn]]))


;; transport-level vocabulary; tiers merge their own {:error kw -> status} via :errors
(def ^:private base-errors {:request/malformed 400})

(defn- println-log [level message data]
  (println (str "[" (name level) "] " message) data))


(defn- wire-format [req]
  (if (some->> (:query-string req) (re-find #"(?:^|&)format=edn"))
    :edn
    :json))

(defn- render
  "A data :body picks its wire form; anything else (file, stream, string) is
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


(defn- handler [{:keys [handlers errors log]} req]
  (render
    (wire-format req)
    (try
      (let [parts (->> (str/split (str (:uri req)) #"/") (remove str/blank?) vec)
            body  (when (= :post (:request-method req)) (json->edn (:body req)))]
        (or (some #(% req parts body) handlers)
            {:status 404 :body {:error "not found"}}))
      (catch clojure.lang.ExceptionInfo e
        (if-let [status (get errors (:error (ex-data e)))]
          (do (log :warn "http: rejected" {:uri (:uri req) :error (ex-message e)})
              {:status status :body {:error (ex-message e)}})
          (do (log :error "http: handler failed" {:uri (:uri req) :error (ex-message e)})
              {:status 500 :body {:error "internal error"}})))
      (catch Throwable e
        (log :error "http: handler failed" {:uri (:uri req) :error (ex-message e)})
        {:status 500 :body {:error "internal error"}}))))


(defn app
  "The composed ring handler (tier handlers + error mapping); listen! serves
   it, the unit test calls it directly."
  [{:keys [handlers errors log]}]
  (partial handler {:handlers handlers
                    :errors   (merge base-errors errors)
                    :log      (or log println-log)}))


(defn listen!
  "Bind host:port and serve; returns http-kit's stop fn. A taken port throws —
   the caller dies loudly and systemd rebuilds it."
  [{:keys [host port log] :as cfg :or {host "127.0.0.1" port 1337}}]
  ((or log println-log) :info "http listening" {:host host :port port})
  (http/run-server (app cfg) {:ip host :port port}))
