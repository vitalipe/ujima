(ns ujima.desktop.http.converge
  "The GUI's converge ports and the streams they feed. control hands it the
   whole settings plane, so this projects; the app layer projects before its
   targets run, so that one republishes as-is."
  (:require [lib.util        :refer [next-of]]
            [lib.http.ndjson :as ndjson]))


(defn settings->ui
  "Effective settings -> the UI blob ([:audio :active] is the truth for which
   output). Presentation derivations belong here — :next is the switcher's
   cycle order, not a domain fact."
  [settings]
  (let [output  (get settings [:audio :active])
        layout  (get settings [:keyboard :layout])
        layouts (get settings [:keyboard :available-layouts])]
    {:audio    {:volume (when output (get settings [:audio output :volume]))
                :muted  (get settings [:audio :muted])
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
