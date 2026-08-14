(ns ujima.desktop.http.app
  "The /ui/apps GUI edge, plus the cycle verbs — a keybind reaching for the
   next window is shell interaction, not something a console sends. a converge target on app's (next prv) stream — broadcast the new
   snapshot when it differs from the previous, and hold it so a new subscriber greets with the
   latest (before the first converge: empty; boot converges before any client connects)."
  (:require [org.httpkit.server :as http]
            [lib.edn            :refer [edn->json]]
            [ujima.desktop.app  :as app]))


(defonce ^:private subs* (atom #{}))
(defonce ^:private last* (atom nil))   ; the last snapshot, for greeting a new subscriber

(defn- line [snapshot] (str (edn->json snapshot) "\n"))

(def ^:private empty-snapshot {:apps [] :current nil})


(defn converge!
  "Converge target (fn [next prv]): broadcast NEXT when it differs from PRV; remember it for the
   next subscriber's greeting."
  [next prv]
  (reset! last* next)
  (when (not= next prv)
    (let [l (line next)]
      (doseq [ch @subs*]
        (http/send! ch l false)))))


(defn stream
  [req]
  (http/as-channel req
    {:on-open  (fn [ch]
                 (swap! subs* conj ch)
                 (http/send! ch (line (or @last* empty-snapshot)) false))
     :on-close (fn [ch _] (swap! subs* disj ch))}))


(def routes
  {"POST /app/next" (fn [_] (app/cycle! 1)  {:status 202 :body {}})
   "POST /app/prev" (fn [_] (app/cycle! -1) {:status 202 :body {}})})
