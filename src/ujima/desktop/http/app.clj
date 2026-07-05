(ns ujima.desktop.http.app
  "The apps GUI edge: serialize + fan out. push! is wired into desktop.app by core;
   a new subscriber greets with the last PUBLISHED line — the edge never computes
   (before the first publish: empty; the boot baseline publishes before any client
   connects)."
  (:require [org.httpkit.server :as http]
            [lib.edn            :refer [edn->json]]))


(defonce ^:private subs*      (atom #{}))
(defonce ^:private last-line* (atom nil))

(defn- line [snapshot] (str (edn->json snapshot) "\n"))

(def ^:private empty-line (line {:apps [] :current nil :current-title nil}))


(defn push!
  "Publish a snapshot to the subscribers — deduped on the serialized line, so a
   no-op look costs nothing on the wire."
  [snapshot]
  (let [l (line snapshot)]
    (when (not= l @last-line*)
      (reset! last-line* l)
      (doseq [ch @subs*]
        (http/send! ch l false)))))


(defn stream
  [req]
  (http/as-channel req
    {:on-open  (fn [ch]
                 (swap! subs* conj ch)
                 (http/send! ch (or @last-line* empty-line) false))
     :on-close (fn [ch _] (swap! subs* disj ch))}))
