(ns lib.config
  (:require [lib.io :refer [slurp-edn]]))


(defonce env* (atom nil))


(def default-paths
  ["config/ujima.edn"
   "config/config.local.edn"])


(defn deep-merge [& xs]
  (if (every? map? xs)
    (apply merge-with deep-merge xs)
    (last xs)))


(defn init!
  ([] (init! default-paths {}))
  ([paths-or-overrides]
   (if (map? paths-or-overrides)
     (init! default-paths paths-or-overrides)
     (init! paths-or-overrides {})))
  ([paths overrides]
   (reset! env*
           (apply deep-merge
                  (concat
                    (map #(slurp-edn % {}) paths)
                    [(or overrides {})])))))


(defn env []
  (or @env*
      (throw
        (ex-info "Ujima env was not initialized"
                 {:hint "Call lib.config/init! from the entry point"}))))


(defn get-in-env [& ks]
  (apply get-in (env) ks))
