(ns ujima.desktop.http.app
  "The app side of the /ui tier: the /ui/apps NDJSON stream — a snapshot line on
   connect, then one line per real change (same contract as /ui/state). push! is the
   GUI edge desktop.app publishes through (core wires it via app/set-push!); this ns
   only serializes and fans out."
  (:require [org.httpkit.server :as http]
            [lib.edn            :refer [edn->json]]
            [ujima.desktop.app  :as app]))


(defonce ^:private subs*      (atom #{}))
(defonce ^:private last-line* (atom nil))

(defn- line [snapshot] (str (edn->json snapshot) "\n"))


(defn push!
  "Publish a snapshot to the /ui/apps subscribers — deduped on the serialized line,
   so a no-op derive costs nothing on the wire."
  [snapshot]
  (let [l (line snapshot)]
    (when (not= l @last-line*)
      (reset! last-line* l)
      (doseq [ch @subs*]
        (http/send! ch l false)))))


(defn stream
  "GET /ui/apps."
  [req]
  (http/as-channel req
    {:on-open  (fn [ch]
                 (swap! subs* conj ch)
                 (http/send! ch (line (app/snapshot-now)) false))
     :on-close (fn [ch _] (swap! subs* disj ch))}))
