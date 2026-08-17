(ns lib.http.signature
  "The signed-request wire format: canonical strings, HMAC, and the two headers.
   Pure — strings and maps, no HTTP and no domain vocabulary.

   Both ends run THIS namespace. Everything either end could implement twice
   lives here, because a disagreement about the format is a silent auth failure
   that reads like a wrong key. Policy — the freshness window, the nonce cache,
   which routes are gated — is NOT here: each side owns its own."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest SecureRandom]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.util HexFormat]))


(def request-header  "authorization")
(def response-header "response-signature")

;; one scheme token both directions: one thing to bump, one thing to compare.
;; It also names the scheme on `authorization`, which is shared with every other
;; auth scheme, so it stays distinctive rather than becoming a bare "v1".
(def ^:private scheme "HMAC1")

(def ^:private hex (HexFormat/of))


;; ── canonical strings ───────────────────────────────────────────────────────

(defn- sha256-hex [^String s]
  (.formatHex hex (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))))


(defn- line
  ;; a newline inside a field would shift every boundary after it, so two
  ;; different requests could canonicalise identically
  [v]
  (let [s (str v)]
    (when (str/includes? s "\n")
      (throw (ex-info "newline in a signed field" {:error :signature/bad-field :value s})))
    s))


(defn- text [tag fields]
  (str/join "\n" (cons tag (map line fields))))


(defn- request-text
  "TARGET is who the request is FOR: one key is shared by every party, so without
   it a captured signature is valid against all of them."
  [{:keys [method path target ts nonce body]}]
  (text "req-v1"
        [(str/upper-case (name method)) path target ts nonce (sha256-hex (str body))]))


(defn- response-text
  "NONCE is the caller's, echoed — an absent one signs as empty, which is what an
   unsigned caller gets. STATUS is covered so a rejection cannot be flipped into
   an apparent success."
  [{:keys [status path nonce body]}]
  (text "res-v1"
        [status path nonce (sha256-hex (str body))]))


;; ── hmac ────────────────────────────────────────────────────────────────────

(defn- hmac
  ;; the key's characters ARE the key — no hex decode, so nothing can fail to parse
  [^String key ^String signed-text]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes key "UTF-8") "HmacSHA256"))
    (.formatHex hex (.doFinal mac (.getBytes signed-text "UTF-8")))))


(defn- same?
  ;; constant time: `=` on the hex would leak the matching prefix a byte at a time
  [^String a b]
  (boolean
    (when (and a b)
      (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes (str b) "UTF-8")))))


;; Signing and building are ONE step on purpose: nothing can sign a request under
;; the response tag, which would look valid here and fail as a bad signature there.

(defn sign-request    [key parts]     (hmac key (request-text parts)))
(defn sign-response   [key parts]     (hmac key (response-text parts)))

(defn verify-request  [key parts sig] (boolean (when key (same? (hmac key (request-text parts))  sig))))
(defn verify-response [key parts sig] (boolean (when key (same? (hmac key (response-text parts)) sig))))


(defonce ^:private rng (SecureRandom.))


(defn nonce []
  (let [b (byte-array 16)]
    (.nextBytes rng b)
    (.formatHex hex b)))


;; ── headers ─────────────────────────────────────────────────────────────────

(defn- header-value [params]
  (str scheme " " (str/join " " (for [[k v] params] (str (name k) "=" v)))))


(defn request-header-value [{:keys [ts nonce sig]}]
  (header-value [[:ts ts] [:nonce nonce] [:sig sig]]))


(defn response-header-value [{:keys [nonce sig]}]
  (header-value [[:nonce nonce] [:sig sig]]))


(defn parse-header
  "\"<scheme> k=v k=v\" -> {:k \"v\"}; nil when the scheme isn't ours. Values stay
   STRINGS — ts is re-signed as it arrived, so coercing here would make \"007\"
   and \"7\" disagree with the signature."
  [header]
  (when-some [h (some-> header str/trim not-empty)]
    (let [[tag & pairs] (str/split h #"\s+")]
      (when (= scheme tag)
        (into {} (for [pair pairs
                       :let [[k v] (str/split pair #"=" 2)]
                       :when (seq k)]
                   [(keyword k) (or v "")]))))))
