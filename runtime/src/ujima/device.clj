(ns ujima.device
  (:require
    [ujima.log                :as log]
    [ujima.linux.disk         :as disk]
    [ujima.linux.system       :as system]
    [ujima.linux.devicetree   :as devicetree]
    [ujima.device.ab          :as ab]
    [ujima.device.ab.autoboot :as autoboot]))


(defn system->disk
  "The disk this system booted from, found by the scheme's config-partition
   PARTUUID — nil when there is none (a dev host)."
  []
  (when-let [dev (disk/partuuid->disk autoboot/ujima-config-uuid)]
    (autoboot/->disk {:device dev})))


(defn init!
  "Machine reality, converged ONCE at start: the serial-derived hostname (never
   a setting — a live rename breaks chromium's profile locks) and the disk's
   first-boot id stamp. Dev hosts (no devicetree, nil disk) are left alone."
  [disk]
  
  (when-let [hostname (some->> (devicetree/serial-tail) (str "ujima-"))]
    (when (not= hostname (system/hostname))
      (log/info "init: renaming the host" {:to hostname})
      (system/hostname! hostname)))
  
  (when disk
    (ab/system-disk-id! disk)))
