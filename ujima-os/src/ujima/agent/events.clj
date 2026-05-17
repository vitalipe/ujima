(ns ujima.agent.events
  (:require [ujima.log            :as log]
            [ujima.system.runtime :as runtime]))


(defn on-control-token-change! [runtime* token]

  (when (:present? token)
    (log/info "control token present, open admin app!"))

  (when-not (:present? token)
    (log/info "control token missing, close admin app!")))
