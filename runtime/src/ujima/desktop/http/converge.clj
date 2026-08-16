(ns ujima.desktop.http.converge
  "The GUI's converge ports and the streams they feed. control hands it the
   whole settings plane, so this projects; the app layer projects before its
   targets run, so that one republishes as-is."
  (:require [lib.util        :refer [next-of]]
            [lib.http.ndjson :as ndjson]))


(defn- effective [settings key] (:effective (get settings key)))


(defn settings->ui
  "Settings records -> the UI blob. Presentation derivations belong here —
   :next is the switcher's cycle order, not a domain fact."
  [settings]
  (let [output  (effective settings [:audio :active])
        layout  (effective settings [:keyboard :layout])
        layouts (effective settings [:keyboard :available-layouts])]
    {:audio    {:volume (when output (effective settings [:audio output :volume]))
                :muted  (effective settings [:audio :muted])
                :output output}
     :keyboard {:layout  layout
                :layouts layouts
                :next    (next-of layouts layout)}}))


(defn converge-ui!   [settings _prv] (ndjson/publish! :ui/state (settings->ui settings)))
(defn converge-apps! [snapshot _prv] (ndjson/publish! :ui/apps  snapshot))

(defn stream-ui   [req] (ndjson/subscribe! :ui/state req))
(defn stream-apps [req] (ndjson/subscribe! :ui/apps  req))


(defn init!
  "Declare the topics before anything serves or converges."
  []
  (ndjson/topic! :ui/state)
  (ndjson/topic! :ui/apps {:apps [] :current nil}))
