(ns ujima.target
  (:require
   [ujima.target.mock :as mock]
   [ujima.target.rpi :as rpi]))


(defn ->runtime [env]
  (let [target (get-in env [:runtime :target])]
    (case target
      :mock (mock/->runtime (get-in env [:runtime :mock]))
      :rpi  (rpi/->runtime (get-in env [:runtime :rpi])))))
