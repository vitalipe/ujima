(ns ujima.linux.disk.mount
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [lib.shell :refer [$?]]
            [ujima.linux.sudo :refer [sudo$! sudo$?]]))


(defn mount-point? [mnt]
  (:ok? ($? mountpoint -q [mnt])))


(defn device->mount-points [device-path]
  (->> ($? findmnt --source [device-path]
                   --output "TARGET"
                   --noheadings
                   --raw)
    (:out)
    (str/split-lines)
    (remove str/blank?)))


(defn device-mounted? [device-path]
  (not (empty? (device->mount-points device-path))))


(defn mounted? [mnt]
  (or (mount-point? mnt)
      (device-mounted? mnt)))


(defn umount! [mnt]
  (sudo$! umount [mnt]))


(defn umount-lazy!
  "Detach now, release when the last user lets go — the umount that survives a yanked device,
   where a plain one can block forever. Returns ok?; an empty mount point is not an error."
  [mnt]
  (:ok? (sudo$? umount -l [mnt])))


(defn mount!
  "OPTS is a coll of -o flags or a ready comma string; DEVICE may be a map, glued to one token:

     (mount! \"vfat\" {:UUID \"6962-5E15\"} \"/mnt/x\" [\"ro\" \"nosuid\"])
     -> mount -t vfat -o ro,nosuid UUID=6962-5E15 /mnt/x"
  ([fs-type device mnt] (mount! fs-type device mnt nil))
  ([fs-type device mnt opts]
   (cond
     (string? opts) (sudo$! mount -t [fs-type] -o [opts] [device] [mnt])
     (seq opts)     (sudo$! mount -t [fs-type] -o (str/join "," opts) [device] [mnt])
     :no-opts       (sudo$! mount -t [fs-type] [device] [mnt]))))


(defn wait-until-unmounted-or-fail! [mnt]
  (loop [wait-ms-left 3000]
    (cond
      (not (mounted? mnt)) true
      (pos? wait-ms-left)     (do
                                (Thread/sleep 50)
                                (recur (- wait-ms-left 50)))

        :timeout-error     (throw
                             (ex-info "Mount point is still mounted after umount"
                                      {:mount-point (str mnt)})))))


(defn with-mounted* [fs-type device f]
  ;; we don't use fs/with-temp-dir here because if we fail to unmount
  ;; we don't want to delete the entire tree
  (let [mnt (fs/create-temp-dir {:dir "/tmp" :prefix "ujima-tmp-mnt-"})]
    (try
      (mount! fs-type device mnt)
      (f mnt)

      (finally
        (when (mounted? mnt)
          (umount! mnt)
          (wait-until-unmounted-or-fail! mnt))

        ;; now we can cleanup tmpdir like a peasant
        (when (fs/exists? mnt)
          (fs/delete mnt)))))) ;; will fail if not empty


(defmacro with-mounted
  [[mnt-sym fs-type device] & body]
  `(with-mounted* ~fs-type ~device
     (fn [~mnt-sym]
       ~@body)))


(defmacro with-mounted-ext4
  [[mnt-sym device] & body]
  `(with-mounted [~mnt-sym "ext4" ~device]
     ~@body))


(defmacro with-mounted-vfat
  [[mnt-sym device] & body]
  `(with-mounted [~mnt-sym "vfat" ~device]
     ~@body))
