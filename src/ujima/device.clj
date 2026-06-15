(ns ujima.device
  (:require
    [ujima.device.ab.autoboot :as autoboot]))


(defn ->disk [_] ;; FIXME: hardcoded for now
  (autoboot/->disk {:device "/dev/mmcblk0"}))


(defn ->boot [_]
  (autoboot/->boot))