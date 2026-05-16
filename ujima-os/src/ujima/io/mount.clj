(ns ujima.io.mount
  (:require [babashka.fs :as fs]
            [ujima.io :as io]))

(defn- mounted? [mnt]
  (:ok? (io/sh :findmnt "-rn" "--mountpoint" (str mnt))))


(defn- temp-mount-dir! [prefix]
  (str (fs/create-temp-dir {:dir "/tmp" :prefix prefix})))


(defn- mount! [fs-type device mnt]
  (io/sudo! :mount "-t" fs-type (str device) (str mnt)))


(defn- umount! [mnt]
  (io/sudo! :umount (str mnt)))


(defn with-mounted* [fs-type device f]
  (let [mnt (temp-mount-dir! (str "ujima-" fs-type "-"))]
    (try
      (mount! fs-type device mnt)
      (f mnt)

      (finally
        (when (mounted? mnt)
          (umount! mnt))

        (when (and (fs/exists? mnt)
                   (not (mounted? mnt)))
          (fs/delete-tree mnt))))))


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