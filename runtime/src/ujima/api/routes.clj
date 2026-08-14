(ns ujima.api.routes
  "Route builders for the /api families: a table in, a plain route map out.

   COMMANDS are keyed by path with :slug holes, or a trailing ** whose tail
   arrives as :path. Params are one open map over
   body, query and slug merged in that order, so a body key cannot rewrite the
   URL. A slug the shape rejects returns nil — that URL was never ours, so it
   404s; anything else is a humanized :request/malformed. :reply present -> 200
   with the handler's return, absent -> 202.

   QUERIES are one ** route over nodes keyed by path (\"system/clock-ms\", or
   \"\" for the root — the URL's own syntax). The deepest node prefixing the
   request answers and the remainder is get-in'd out of it, so a node must
   carry every key it declares, nil-filled, or a real nil reads as a typo.
   Above every node the answer is assembled from those below, which is what
   makes a table a view rather than a mirror. Overlap is legal, deepest wins.
   A collection answers bare, a scalar wears the key it was asked for."
  (:require [clojure.string  :as str]
            [lib.util        :refer [map-keys map-kv-vals]]
            [malli.core      :as m]
            [malli.error     :as me]
            [malli.transform :as mt]))


(defn- ->path
  "\"system/clock-ms\" -> [:system :clock-ms]; blank is the root."
  [s]
  (if (str/blank? s) [] (mapv keyword (str/split s #"/"))))


(defn- slugs-in [path]
  (->> (str/split path #"/")
       (filter #(str/starts-with? % ":"))
       (mapv #(keyword (subs % 1)))))


(defn- ->route [base path]
  (str "POST /" base "/" (str/replace path #":[^/]+" "*")))


(defn- ->params
  "Slugs by name, and a ** tail as :path — the caught values are positional,
   the tail last."
  [req slugs tail?]
  (let [caught (:path-params req)
        named  (zipmap slugs caught)]
    (merge (:body req)
           (:query req)
           (if tail?
             (assoc named :path (->path (when (> (count caught) (count slugs)) (last caught))))
             named))))


(defn- sentence [explanation]
  (let [[k msgs] (first (me/humanize explanation))]
    (str (name k) " " (first (flatten [msgs])))))


(defn- conform!
  "The clean params, the 400 throw, or nil when a slug is what failed."
  [shape slug? params]
  (let [clean (m/decode shape params mt/string-transformer)
        bad   (m/explain shape clean)]
    (cond
      (nil? bad)                                        clean
      (some (comp slug? first :in) (:errors bad))       nil
      :else (throw (ex-info (sentence bad)
                            {:error :request/malformed :params params})))))


(defn- ->command [path {:keys [handler reply] shape :params}]
  (assert handler (str "no :handler for " path))
  (let [slugs (slugs-in path)
        tail? (str/ends-with? path "**")
        gate  (if shape (partial conform! shape (set slugs)) identity)]
    (fn [req]
      (when-some [params (gate (->params req slugs tail?))]
        (if reply
          {:status 200 :body (handler params)}
          (do (handler params)
              {:status 202 :body {}}))))))


(defn commands
  "BASE (\"commands\") + the command table -> a route map, mount-relative."
  [& {:keys [base] table :commands}]
  (->> table
       (map-kv-vals ->command) ; while the key still names its slugs
       (map-keys (partial ->route base))))


;; ── queries ─────────────────────────────────────────────────────────────────

(defn- prefix? [short long]
  (= short (vec (take (count short) long))))


(defn- deepest [nodes path]
  (->> (keys nodes) (filter #(prefix? % path)) (sort-by count) last))


(defn- assemble
  "Everything below PATH as one tree; nil when nothing is."
  [nodes path]
  (reduce (fn [tree k] (assoc-in tree (vec (drop (count path) k)) ((nodes k))))
          nil
          (filter #(prefix? path %) (keys nodes))))


(defn queries
  "BASE (\"query/machine\") + the node table -> a route map, mount-relative."
  [& {:keys [base] table :nodes}]
  (let [nodes (map-keys ->path table)]
    {(str "GET /" base "/**")
     (fn [req]
       (let [path (->path (first (:path-params req)))
             node (deepest nodes path)
             v    (if node
                    (get-in ((nodes node)) (vec (drop (count node) path)) ::miss)
                    (or (assemble nodes path) ::miss))]
         (cond
           (= ::miss v) {:status 404 :body {:error "not found"}}
           (coll? v)    {:status 200 :body v}
           :else        {:status 200 :body {(last path) v}})))}))
