(ns lib.http.ndjson
  "Topics with http-kit at the edge: publish a snapshot, every subscriber gets a
   json line. A topic remembers its last snapshot, so a new subscriber is
   greeted with it and republishing the same value says nothing."
  (:require [org.httpkit.server :as http]
            [lib.edn            :refer [edn->json]]))


(defonce ^:private topics* (atom {}))   ; k -> {:subs #{ch} :last snapshot}


(defn- line [snapshot] (str (edn->json snapshot) "\n"))


(defn topic!
  "Declare K, optionally with the snapshot to greet with before anything is
   published. Idempotent: re-declaring keeps the subscribers. Returns K."
  ([k] (topic! k nil))
  ([k default]
   (swap! topics* update k #(merge {:subs #{} :last default} %))
   k))


(defn publish! [k snapshot]
  (let [{:keys [subs last]} (get @topics* k)]
    (when (not= snapshot last)
      (swap! topics* assoc-in [k :last] snapshot)
      (let [l (line snapshot)]
        (doseq [ch subs] (http/send! ch l false))))))


(defn subscribe!
  "Hold REQ open: greet with the last snapshot if there is one, then keep it
   until it closes."
  [k req]
  (http/as-channel req
    {:on-open  (fn [ch]
                 (swap! topics* update-in [k :subs] conj ch)
                 (when-some [snapshot (:last (get @topics* k))]
                   (http/send! ch (line snapshot) false)))
     :on-close (fn [ch _] (swap! topics* update-in [k :subs] disj ch))}))
