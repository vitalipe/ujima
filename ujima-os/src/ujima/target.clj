(ns ujima.target
  (:require
   [ujima.target.mock :as mock-runtime]
   [ujima.target.rpi.runtime :as rpi-runtime]))


(defn ->runtime [env]
  (let [target (get-in env [:runtime :target])]
    (case target
      :mock (mock-runtime/->runtime (get-in env [:runtime :mock]))
      :rpi  (rpi-runtime/->runtime (get-in env [:runtime :rpi])))))
