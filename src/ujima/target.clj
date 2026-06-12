(ns ujima.target
  (:require
   [ujima.target.mock :as mock-runtime]
   [ujima.target.rpi.runtime :as rpi-runtime]))


(defn ->runtime [env]
  (case (:target env)
    :mock (mock-runtime/->runtime (:mock env))
    :rpi  (rpi-runtime/->runtime  (:rpi env))))
