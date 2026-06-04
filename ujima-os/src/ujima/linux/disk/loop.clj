(ns ujima.linux.disk.loop
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [cheshire.core :as json]

            [ujima.linux.shell      :refer [sh sudo$!]]
            [ujima.linux.disk       :refer [device->partitions]]
            [ujima.linux.disk.mount :refer [device->mount-points]]))


(defn path->loopback-devices [image-path]
  (let [{:keys [out]} (sh :losetup "-j" (str image-path))]
    (->> (str/split-lines out)
         (map #(first (str/split % #":" 2)))
         (remove str/blank?)
         (into []))))


(defn loopback-devices []
  (let [out (:out (sh :losetup "--list" "--json"))]
    (-> out
        (json/parse-string true)
        (:loopdevices))))


(defn attach-loopback-device! 
  ([image-path] 
   (attach-loopback-device! image-path false))
  
  ([image-path readonly?]
   (when-not (fs/exists? image-path)
     (throw (ex-info "Image file does not exist"
                     {:image-path image-path})))

   (if readonly?
     (sudo$! losetup --read-only --find --show --partscan [image-path])
     (sudo$! losetup --find --show --partscan [image-path]))))


(defn detach-loopback-device! [device]
  (let [mnt-points (->> device device->partitions
                     (mapcat device->mount-points))]
    
    (when-not (empty? mnt-points)
      (throw
        (ex-info "Device child partitions are mounted"
                 {:device device :mount-points mnt-points}))))

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