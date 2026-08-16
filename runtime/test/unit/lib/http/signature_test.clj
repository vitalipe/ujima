(ns lib.http.signature-test
  "The wire format, pinned. These are GOLDEN vectors, not round-trips: a
   round-trip passes even when the format changes on both sides at once, which
   is exactly the change that stops half-upgraded parties from talking. If a
   field moves or a tag changes, these fail — deliberately.

   The signed text is private, so the string assertions reach it through its var."
  (:require [clojure.test   :refer [deftest is testing]]
            [clojure.string :as str]
            [lib.http.signature :as sig]))


(def ^:private request-text  #'sig/request-text)
(def ^:private response-text #'sig/response-text)


(def ^:private signing-key
  "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")

(def ^:private req
  {:method :post
   :path   "/api/commands/audio/volume"
   :target "node-a-3f9c1a"
   :ts     1755290417331
   :nonce  "b7c1e2f4a91d6035"
   :body   "{\"scope\":\"session\",\"value\":40}"})

(def ^:private res
  {:status 202
   :path   "/api/commands/audio/volume"
   :nonce  "b7c1e2f4a91d6035"
   :body   "{}"})


(deftest request-format-is-frozen
  (is (= (str "req-v1\n"
              "POST\n"
              "/api/commands/audio/volume\n"
              "node-a-3f9c1a\n"
              "1755290417331\n"
              "b7c1e2f4a91d6035\n"
              "dbf88306e5c25cba7985bdeb902cb0b51d1e49cb19df51ad1335532c0be0e977")
         (request-text req)))

  (is (= "889f083bf47044c65d8185c938885019cd9b7546081aa4d35cb0b62d03c82ab0"
         (sig/sign-request signing-key req))))


(deftest response-format-is-frozen
  (is (= (str "res-v1\n"
              "202\n"
              "/api/commands/audio/volume\n"
              "b7c1e2f4a91d6035\n"
              "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")
         (response-text res)))

  (is (= "41ec1ea61931964a7ff70fd540374bfc954b1fced7e0b3ecaaff751cb990f149"
         (sig/sign-response signing-key res))))


(deftest a-missing-body-hashes-as-empty
  (is (= (sig/sign-request signing-key (assoc req :body ""))
         (sig/sign-request signing-key (assoc req :body nil)))))


(deftest an-absent-nonce-signs-as-empty
  ;; what an unsigned caller gets: still signed, just replayable
  (is (str/includes? (response-text (assoc res :nonce nil))
                     "res-v1\n202\n/api/commands/audio/volume\n\n")))


(deftest the-target-is-inside-the-signature
  ;; one key is shared by every party — without this, one capture works on all
  (is (not= (sig/sign-request signing-key req)
            (sig/sign-request signing-key (assoc req :target "node-b-77ac10")))))


(deftest the-status-is-inside-the-signature
  ;; so a rejection cannot be flipped into an apparent success
  (is (not= (sig/sign-response signing-key res)
            (sig/sign-response signing-key (assoc res :status 409)))))


(deftest req-and-res-are-domain-separated
  ;; distinct leading tags: a response can never be replayed as a request
  (is (str/starts-with? (request-text  req) "req-v1\n"))
  (is (str/starts-with? (response-text res) "res-v1\n")))


(deftest verify-accepts-only-the-real-thing
  (let [good (sig/sign-request signing-key req)]
    (is (true?  (sig/verify-request signing-key req good)))
    (is (false? (sig/verify-request signing-key req (str/replace good #"^88" "aa"))))
    (is (false? (sig/verify-request "a-different-key" req good)))
    (is (false? (sig/verify-request signing-key (assoc req :ts 1755290417332) good)))
    (is (false? (sig/verify-request signing-key req nil)))
    (is (false? (sig/verify-request nil req good))))

  (let [good (sig/sign-response signing-key res)]
    (is (true?  (sig/verify-response signing-key res good)))
    (is (false? (sig/verify-response signing-key (assoc res :status 409) good)))))


(deftest a-newline-in-a-field-is-refused
  ;; it would shift every boundary after it, so two requests could sign the same
  (is (thrown? clojure.lang.ExceptionInfo
        (sig/sign-request signing-key (assoc req :path "/api/x\nnode-b-77ac10"))))
  (is (= :signature/bad-field
         (try (sig/sign-request signing-key (assoc req :target "a\nb"))
              (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))))


(deftest headers-round-trip
  (testing "request"
    (let [h (sig/request-header-value {:key-id "a3f1c2d40e91" :ts 1755290417331
                                       :nonce "b7c1e2f4a91d6035" :sig "abc123"})]
      (is (= "HMAC1 key=a3f1c2d40e91 ts=1755290417331 nonce=b7c1e2f4a91d6035 sig=abc123" h))
      (is (= {:key "a3f1c2d40e91" :ts "1755290417331"
              :nonce "b7c1e2f4a91d6035" :sig "abc123"}
             (sig/parse-header h)))))

  (testing "response"
    (let [h (sig/response-header-value {:nonce "b7c1e2f4a91d6035" :sig "abc123"})]
      (is (= "HMAC1 nonce=b7c1e2f4a91d6035 sig=abc123" h))
      (is (= {:nonce "b7c1e2f4a91d6035" :sig "abc123"} (sig/parse-header h))))))


(deftest ts-stays-a-string
  ;; re-signed exactly as it arrived: coercing would make "007" and "7" disagree
  (is (= "007" (:ts (sig/parse-header "HMAC1 key=k ts=007 nonce=n sig=s")))))


(deftest a-foreign-header-is-not-ours
  (is (nil? (sig/parse-header "Bearer abc")))
  (is (nil? (sig/parse-header "")))
  (is (nil? (sig/parse-header nil))))


(deftest nonces-are-hex-and-distinct
  (let [nonces (repeatedly 50 sig/nonce)]
    (is (every? #(re-matches #"^[0-9a-f]{32}$" %) nonces))
    (is (= 50 (count (set nonces))))))
