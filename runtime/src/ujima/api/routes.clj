(ns ujima.api.routes
  "The ujima API conventions as route builders: a table in, a plain route map
   out — the keys router/router matches, the fns it calls. No router and no
   server here; the caller merges the results with its own routes.

   A COMMAND is keyed by its path, :slug holes for the segments that vary, and
   its params are ONE open map over everything the caller sent — body, query
   and slug merged in that order, so a body key can never rewrite what the URL
   said. All three come off the request the edge already parsed: :body, :query
   and the router's :path-params.

   A slug the shape rejects is a 404 (nil, the caller falls through); anything
   else is a humanized :request/malformed throw the edge maps to 400. :reply
   present -> 200 with the handler's return, absent -> 202 {}.

   QUERIES are one ** route over a table of nodes keyed by path VECTOR, each a
   thunk. The deepest node that prefixes the request answers and the remainder
   is get-in'd out of what it returned — so a node must carry every key it
   declares, nil-filled, or a real nil is indistinguishable from a typo (::miss
   is the 404). Above every node the answer is ASSEMBLED from the nodes below,
   which is what lets a table be a view rather than a mirror. Overlap is legal
   and the deepest wins: a coarse [:desktop] beside a cheap [:desktop :running]
   is a deliberate move, not a mistake. A collection answers bare and a scalar
   wears the key it was asked for, which leaves every body coll? — the same
   test the edge uses to decide what to encode, so nothing can fall through to
   its raw passthrough."
  (:require [clojure.string  :as str]
            [lib.util        :refer [map-keys map-kv-vals]]
            [malli.core      :as m]
            [malli.error     :as me]
            [malli.transform :as mt]))


(defn- slugs-in [path]
  (->> (str/split path #"/")
       (filter #(str/starts-with? % ":"))
       (mapv #(keyword (subs % 1)))))


(defn- ->route [base path]
  (str "POST /" base "/" (str/replace path #":[^/]+" "*")))


(defn- ->params [req slugs]
  (merge (:body req)
         (:query req)
         (zipmap slugs (:path-params req))))


(defn- sentence [explanation]
  (let [[k msgs] (first (me/humanize explanation))]
    (str (name k) " " (first (flatten [msgs])))))


(defn- conform!
  "decode -> validate: the clean params, the 400 throw, or nil when a slug is
   what failed — that URL was never ours."
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
        gate  (if shape (partial conform! shape (set slugs)) identity)]
    (fn [req]
      (when-some [params (gate (->params req slugs))]
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

(defn- ->path [req]
  (let [[tail] (:path-params req)]
    (if (seq tail) (mapv keyword (str/split tail #"/")) [])))


(defn- prefix? [short long]
  (= short (vec (take (count short) long))))


(defn- deepest [nodes path]
  (->> (keys nodes) (filter #(prefix? % path)) (sort-by count) last))


(defn- assemble
  "Everything below PATH, grafted back into one tree; nil when nothing is."
  [nodes path]
  (reduce (fn [tree k] (assoc-in tree (vec (drop (count path) k)) ((nodes k))))
          nil
          (filter #(prefix? path %) (keys nodes))))


(defn queries
  "BASE (\"query/machine\") + the node table -> a route map, mount-relative."
  [& {:keys [base] nodes :nodes}]
  {(str "GET /" base "/**")
   (fn [req]
     (let [path (->path req)
           node (deepest nodes path)
           v    (if node
                  (get-in ((nodes node)) (vec (drop (count node) path)) ::miss)
                  (or (assemble nodes path) ::miss))]
       (cond
         (= ::miss v) {:status 404 :body {:error "not found"}}
         (coll? v)    {:status 200 :body v}
         :else        {:status 200 :body {(last path) v}})))})
