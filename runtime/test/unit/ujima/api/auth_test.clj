(ns ujima.api.auth-test
  "The gate, through the real edge: what a caller must present, what it is told
   when it doesn't, and what comes back signed."
  (:require [clojure.test :refer [deftest is]]
            [lib.http           :as http]
            [lib.http.signature :as sig]
            [ujima.api          :as api]
            [ujima.api.auth     :as auth]))


(def ^:private self-id "node-a-3f9c1a")

;; the key is an input, so the gate is testable without a settings plane behind it
(def ^:private circle-key "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")


(def ^:private auth-cfg {:key circle-key :self-id self-id})

(defn- app []
  (http/app {:endpoints {"api" (api/endpoints {:id self-id :gate (auth/->gate auth-cfg)})}
             :log  (fn [& _])
             :sign (auth/->sign auth-cfg)}))


(defn- request
  "A request, signed unless :unsigned. Knobs let a test break exactly one thing."
  [method uri & {:keys [body target ts nonce unsigned tamper]}]
  (let [raw       (or body "")
        target    (or target self-id)
        ts        (str (or ts (System/currentTimeMillis)))
        nonce     (or nonce (sig/nonce))
        signature (sig/sign-request circle-key
                                    {:method method :path uri :target target
                                     :ts ts :nonce nonce :body raw})
        signature (if tamper
                    (str (if (= \a (first signature)) \b \a) (subs signature 1))
                    signature)
        base      (cond-> {:request-method method :uri uri :raw-body raw :query-string "format=edn"}
                    body (assoc :body body))]
    (if unsigned
      base
      (assoc-in base [:headers sig/request-header]
                (sig/request-header-value
                  {:ts ts :nonce nonce :sig signature})))))


(defn- answer [req] (let [r ((app) req)] (assoc r :body (read-string (:body r)))))


(deftest an-unsigned-request-is-refused-and-told-how
  (let [r (answer (request :post "/api/commands/app/home" :unsigned true))]
    (is (= 401 (:status r)))
    (is (= "auth/unsigned" (:reason (:body r))))
    (is (= "HMAC1" (get-in r [:headers "WWW-Authenticate"])) "so a bare curl says what to bring")))


(deftest a-wrong-signature-is-refused
  (is (= "auth/bad-signature"
         (:reason (:body (answer (request :post "/api/commands/app/home" :tamper true)))))))


(deftest a-signature-for-another-machine-does-not-work-here
  ;; the whole reason target is inside the signature: one key, many machines
  (is (= "auth/bad-signature"
         (:reason (:body (answer (request :post "/api/commands/app/home" :target "node-b-77ac10")))))))


(deftest a-tampered-body-is-refused
  (let [signed (request :post "/api/commands/audio/volume" :body "{\"scope\":\"session\",\"value\":40}")
        swapped (assoc signed :body "{\"scope\":\"session\",\"value\":100}"
                              :raw-body "{\"scope\":\"session\",\"value\":100}")]
    (is (= "auth/bad-signature" (:reason (:body (answer swapped)))))))


(deftest a-stale-timestamp-comes-back-with-our-clock
  (let [r (answer (request :post "/api/commands/app/home" :ts (- (System/currentTimeMillis) 600000)))]
    (is (= 409 (:status r)))
    (is (= "auth/stale" (:reason (:body r))))
    (is (number? (:clock-ms (:body r))) "so one retry fixes a caller that has never met us")))


(deftest a-reused-nonce-is-refused
  (let [nonce (sig/nonce)
        once  #(answer (request :post "/api/commands/app/home" :nonce nonce))]
    (is (not= 409 (:status (once))) "the first is fine")
    (is (= "auth/replay" (:reason (:body (once)))) "the second is not")))


(deftest the-open-tier-needs-no-signature
  (let [r (answer (request :get "/api/query/machine/system/clock-ms" :unsigned true))]
    (is (= 200 (:status r)) "a caller needs this BEFORE it can stamp a request")
    (is (number? (:clock-ms (:body r))))))


(deftest the-settings-tier-does-need-one
  (is (= 401 (:status (answer (request :get "/api/query/settings" :unsigned true))))))


(deftest every-response-is-signed-and-bound-to-the-callers-nonce
  (let [nonce "b7c1e2f4a91d6035"
        r     ((app) (request :get "/api/query/machine/id" :nonce nonce))
        parts (sig/parse-header (get-in r [:headers sig/response-header]))]
    (is (= nonce (:nonce parts)) "echoed, so a captured answer cannot serve the next question")
    (is (sig/verify-response circle-key
                             {:status (:status r) :path "/api/query/machine/id"
                              :nonce nonce :body (:body r)}
                             (:sig parts)))))


(deftest even-a-refusal-is-signed
  ;; an unsigned 401 would be indistinguishable from one a stranger injected
  (let [r ((app) (request :post "/api/commands/app/home" :tamper true))]
    (is (some? (get-in r [:headers sig/response-header])))))


(deftest an-unsigned-caller-still-gets-a-signed-answer
  (let [r     ((app) (request :get "/api/query/machine/id" :unsigned true))
        parts (sig/parse-header (get-in r [:headers sig/response-header]))]
    (is (= "" (:nonce parts)) "nothing to bind to, so it is replayable — and they aren't checking")
    (is (sig/verify-response circle-key
                             {:status 200 :path "/api/query/machine/id" :nonce "" :body (:body r)}
                             (:sig parts)))))
