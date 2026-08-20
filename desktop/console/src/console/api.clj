(ns console.api
  "One machine over HTTP: a signed request, its verified answer. Every request is
   signed, gated or not — one client rule, nothing to drift. A first-contact probe
   names no target, which an open route never checks."
  (:require [babashka.http-client :as http]
            [clojure.edn          :as edn]
            [lib.edn              :refer [edn->json]]
            [lib.http.signature   :as sig]))


(def ^:private port 1337)

;; a command must resolve inside the jobs deadline (5s)
(def timeouts {:probe 1000 :poll 1500 :command 4000})


;; peer id -> the clock we last read from it: requests stamp from the TARGET's
(defonce ^:private clocks* (atom {}))

(defn- note-clock! [id clock-ms]
  (when (and id clock-ms)
    (swap! clocks* assoc id {:clock-ms clock-ms :at (System/currentTimeMillis)})))

(defn- stamp
  "The target's clock now; ours until we have heard from it, which one stale retry fixes."
  [id]
  (if-let [{:keys [clock-ms at]} (@clocks* id)]
    (+ clock-ms (- (System/currentTimeMillis) at))
    (System/currentTimeMillis)))

;; the machine tree carries it under :system, a stale refusal at the top
(defn- clock-in [answer]
  (or (get-in answer [:system :clock-ms]) (:clock-ms answer)))


(defn- one-shot
  "Never throws: a dead socket, or no key to sign with, is a verdict."
  [{:keys [key id addr]} method uri body timeout-ms]
  (if-not key
    {:verdict :keyless}
    (let [nonce (sig/nonce)
          ts    (str (stamp id))
          signed (sig/sign-request key {:method method :path uri :target id
                                        :ts     ts     :nonce nonce :body (or body "")})]
      (try
        (let [{:keys [status headers body]}
              (http/request (cond-> {:method  method
                                     :uri     (str "http://" addr ":" port uri "?format=edn")
                                     :headers {sig/request-header
                                               (sig/request-header-value {:ts ts :nonce nonce :sig signed})}
                                     :timeout timeout-ms
                                     :throw   false}
                              body (assoc :body body)))]
          (if (sig/verify-response key
                                   {:status status :path uri :nonce nonce :body body}
                                   (:sig (sig/parse-header (get headers sig/response-header))))
            {:verdict :answered
             :http    status
             :answer  (try (edn/read-string body) (catch Exception _ nil))}
            {:verdict :unverified}))
      (catch Exception _ {:verdict :noreply})))))


(defn- call
  "One round trip, and a second only when the peer says we stamped it stale."
  ([peer method uri body timeout-ms] (call peer method uri body timeout-ms true))
  ([peer method uri body timeout-ms retry?]
   (let [{:keys [verdict http answer]} (one-shot peer method uri body timeout-ms)]
     (note-clock! (:id peer) (clock-in answer))
     (case verdict
       :keyless    {:status :fail    :data {:reason :no-key}}
       :noreply    {:status :noreply :data {:reason :transport}}
       :unverified {:status :fail    :data {:reason :auth/bad-response}}
       :answered   (cond
                     (<= 200 http 299)
                     (cond-> {:status :ok} (seq answer) (assoc :data answer))

                     (and retry? (= 409 http) (= "auth/stale" (:reason answer)))
                     (call peer method uri body timeout-ms false)

                     :otherwise
                     {:status :fail :data {:reason (or (:reason answer) http)}})))))


(defn machine
  "A peer's whole machine tree."
  [peer]
  (call peer :get "/api/query/machine" nil (:poll timeouts)))

(defn probe
  "First contact: an id, and — the answer must verify — proof of our key."
  [key addr]
  (call {:key key :addr addr} :get "/api/query/machine/id" nil (:probe timeouts)))

(defn command!
  "PATH is command-relative. A clear is its whole URL, so it carries no body."
  ([peer path] (command! peer path nil))
  ([peer path body]
   (call peer :post (str "/api/commands/" path) (some-> body edn->json) (:command timeouts))))
