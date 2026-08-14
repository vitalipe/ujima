(ns ujima.device
  (:require
    [ujima.linux.disk :as disk]
    [ujima.device.ab.autoboot :as autoboot]))


(defn system->disk
  "The disk this system booted from, found by the scheme's config-partition
   PARTUUID — nil when there is none (a dev host)."
  []
  (when-let [dev (disk/partuuid->disk autoboot/ujima-config-uuid)]
    (autoboot/->disk {:device dev})))
