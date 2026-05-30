(ns ujima.linux.disk.loop
  (:require [babashka.fs :as fs]
            [ujima.linux.shell :refer [sudo!]]))


(defn attach-loopback-device! [image-path]
  (when-not (fs/exists? image-path)
    (throw (ex-info "Image file does not exist"
                    {:image-path image-path})))

  (sudo$! losetup --find --show --partscan [image-path]))


(defn detach-loopback-device! [device]
  (sudo$! losetup --detach [device]))


(defn with-loopback-device* [image-path f]
  (let [device (attach-loopback-device! image-path)]
    (try
      (f device)
      (finally
        (detach-loopback-device! device)))))


(defmacro with-loopback-device [[device image-path] & body]
  `(with-loopback-device* ~image-path
     (fn [~device]
       ~@body)))