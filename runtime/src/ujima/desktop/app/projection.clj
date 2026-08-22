(ns ujima.desktop.app.projection
  "Pure: the observed world {:focused-ws :ws->wins :catalog} -> the snapshot."
  (:require [ujima.desktop.app.catalog :as catalog]))


(def home-ws "1")


(defn app-of-ws [{:keys [catalog]} ws]
  (when (and ws (not= ws home-ws))
    (first (filter #(= (name %) ws) (:order catalog)))))


(defn open-apps
  "App ids with windows on their workspace, in catalog order — the one definition of 'open'."
  [{:keys [catalog ws->wins]}]
  (filter #(seq (get ws->wins (name %))) (:order catalog)))


(defn entry [{:keys [catalog ws->wins]} id]
  (let [a    (get-in catalog [:by-id id])
        wins (get ws->wins (name id))]
    {:id id :label (:label a) :icon (:icon a) :category (:category a)
     :title (:title (or (first (filter :focused? wins)) (first wins)))
     ;; detected, or declared (:mode) by an app that draws full-screen without setting the state
     :fullscreen (boolean (or (some :fullscreen? wins) (= :fullscreen (:mode a))))}))


(defn projection [{:keys [focused-ws catalog] :as world}]
  {:running (mapv #(entry world %) (open-apps world))
   :catalog (catalog/listing catalog)     ; listing, not raw entries — those hold :env
   :current (when-let [id (app-of-ws world focused-ws)] (entry world id))})
