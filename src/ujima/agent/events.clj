(ns ujima.agent.events
  (:require [ujima.log :as log]))


(defn on-control-token-change! [token]

  (when (:present? token)
    (log/info "control token present, open admin app!"))

  (when-not (:present? token)
    (log/info "control token missing, close admin app!")))
