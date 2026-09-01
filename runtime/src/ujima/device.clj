(ns ujima.device
  (:require
    [ujima.linux.disk         :as disk]
    [ujima.device.ab          :as ab]
    [ujima.device.ab.autoboot :as autoboot]))


(defn system->disk
  "The disk this system booted from, found by the scheme's control-partition
   PARTUUID (slot-agnostic, its index never moves) — nil when there is none
   (a dev host)."
  []
  (when-let [dev (disk/partuuid->disk autoboot/ujima-control-uuid)]
    (autoboot/->disk {:device dev})))


(defn system->boot-runtime
  "How this machine boots, over the disk it booted from."
  []
  (autoboot/->boot-runtime (system->disk)))


(defn init!
  "Machine reality, converged ONCE at start: the disk's first-boot id stamp. The
   hostname is NOT here — the initramfs hook derives it from the board serial before
   PID 1 (os/pipeline/base/identity/ujima-identity), which is the only point early
   enough for avahi. Dev hosts (nil disk) are left alone."
  [disk]
  (when disk
    (ab/system-disk-id! disk)))
