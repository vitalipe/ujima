(ns ujima.desktop.http.app
  "The app side of the /ui tier: the /ui/apps NDJSON stream over the proc store —
   a snapshot line on connect, then one line per real change (same contract as
   /ui/state). The store itself is ujima.desktop.app's; this ns only serializes
   and fans out."
  (:require [org.httpkit.server :as http]
            [lib.edn            :refer [edn->json]]
            [ujima.desktop.app  :as app]))


(defonce ^:private subs*      (atom #{}))
(defonce ^:private last-line* (atom nil))

(defn- line [snapshot] (str (edn->json snapshot) "\n"))


(defn push!
  "Push the current snapshot to the /ui/apps subscribers — deduped on the
   serialized line, so a no-op fold (an unmanaged window's event) costs nothing
   on the wire. Single caller: the events listener thread."
  []
  (let [l (line (app/procs-snapshot))]
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
                 (http/send! ch (line (app/procs-snapshot)) false))
     :on-close (fn [ch _] (swap! subs* disj ch))}))
