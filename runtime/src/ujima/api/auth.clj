(ns ujima.api.auth
  "The signed-request gate for /api, and the signer for everything it answers.

   Two independent rules, on purpose: a signature is REQUIRED only on the routes
   this gates, but every response is SIGNED — including the open ones, because
   query/machine carries the clock a caller stamps its next request with.

   Freshness is measured against THIS machine's clock. A caller stamps with the
   clock it last read from us, so the gap is a round trip however wrong we are
   in absolute terms — and a stale rejection carries our clock, so one retry
   fixes a caller that has never spoken to us before."
  (:require [lib.http.signature :as sig]
            [lib.util           :refer [map-vals]]))


;; a caller stamps from OUR clock, so this bounds a round trip, not clock drift
(def ^:private default-window-ms 60000)

;; nonce -> when we saw it. Bounded by the window: entries older than it are
;; already refused as stale, so they can never be replayed.
(defonce ^:private seen* (atom {}))


(defn- fresh-nonce!
  "false when NONCE was already used inside the window. Records it either way in
   one swap, so two racing copies of a request cannot both be the first."
  [nonce now window-ms]
  (let [cutoff (- now window-ms)]
    (:fresh? (swap! seen*
               (fn [{:keys [cache]}]
                 (let [cache (into {} (remove (fn [[_ seen]] (< seen cutoff))) cache)]
                   (if (contains? cache nonce)
                     {:cache cache :fresh? false}
                     {:cache (assoc cache nonce now) :fresh? true})))))))


(defn- refuse [status reason message & [extra]]
  {:status  status
   :headers (when (= 401 status) {"WWW-Authenticate" "HMAC1"})
   :body    (merge {:error message :reason reason} extra)})


(defn- refusal
  "The response that turns this request away, or nil when it may pass."
  [req {:keys [key self-id window-ms]}]
  (let [{:keys [ts nonce sig]} (sig/parse-header (get-in req [:headers sig/request-header]))
        now   (System/currentTimeMillis)
        stamp (some-> ts parse-long)]
    (cond
      (nil? sig)
      (refuse 401 "auth/unsigned" "request is not signed")

      ;; the signature FIRST: nothing an unsigned caller sends may reach the
      ;; nonce cache, or filling it would be a denial of service
      (not (sig/verify-request key
                               {:method (:request-method req)
                                :path   (:uri req)
                                :target self-id
                                :ts     ts
                                :nonce  nonce
                                :body   (:raw-body req)}
                               sig))
      (refuse 401 "auth/bad-signature" "signature does not match")

      (or (nil? stamp) (> (abs (- now stamp)) window-ms))
      (refuse 409 "auth/stale" "timestamp is outside the window" {:clock-ms now})

      (not (fresh-nonce! nonce now window-ms))
      (refuse 409 "auth/replay" "nonce has already been used"))))


(defn ->gate
  "A fn wrapping a route table so every handler in it answers only to a valid
   signature. KEY is read once, at startup — nothing writes it at runtime, so a
   change to it takes a restart."
  [cfg]
  (let [cfg (update cfg :window-ms #(or % default-window-ms))]
    (fn [routes]
      (map-vals (fn [handler]
                  (fn [req]
                    (or (refusal req cfg)
                        (handler req))))
                routes))))


(defn ->sign
  "lib.http's :sign hook. Bound to the caller's nonce so a captured response
   cannot be replayed at the next question, and absent when they sent none."
  [{:keys [key]}]
  (fn [req resp]
    (let [nonce (or (:nonce (sig/parse-header (get-in req [:headers sig/request-header]))) "")]
      {sig/response-header
       (sig/response-header-value
         {:nonce nonce
          :sig   (sig/sign-response key
                                    {:status (:status resp)
                                     :path   (:uri req)
                                     :nonce  nonce
                                     :body   (:body resp)})})})))
