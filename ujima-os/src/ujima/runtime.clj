(ns ujima.runtime
  (:require
   [ujima.runtime.protocols :as rt]
   [ujima.runtime.target.mock :as mock]
   [ujima.runtime.target.rpi :as rpi]))


(defn ->runtime [env]
  (let [target (get-in env [:runtime :target])]
    (case target
      :mock (mock/->runtime (get-in env [:runtime :mock]))
      :rpi  (rpi/->runtime (get-in env [:runtime :rpi])))))
