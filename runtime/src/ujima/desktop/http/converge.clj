(ns ujima.desktop.http.converge
  "The GUI's converge ports and the streams they feed. control hands it the
   whole settings plane, so this projects; the app layer projects before its
   targets run, so that one republishes as-is."
  (:require [lib.http.ndjson       :as ndjson]
            [ujima.control.queries :as queries]))


(defn settings->ui
  "Settings records -> the UI blob."
  [settings]
  {:audio    (queries/audio-status settings)
   :keyboard {:layout  (:effective (get settings [:keyboard :layout]))
              :layouts (:effective (get settings [:keyboard :available-layouts]))
              :next    (queries/next-keyboard-layout settings)}})


(defn converge-ui!   [settings _prv] (ndjson/publish! :ui/state (settings->ui settings)))
(defn converge-apps! [snapshot _prv] (ndjson/publish! :ui/apps  snapshot))

(defn stream-ui   [req] (ndjson/subscribe! :ui/state req))
(defn stream-apps [req] (ndjson/subscribe! :ui/apps  req))


(defn init!
  "Declare the topics before anything serves or converges."
  []
  (ndjson/topic! :ui/state)
  (ndjson/topic! :ui/apps {:apps [] :current nil}))
