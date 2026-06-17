(ns tools.cmd.loopback
  (:require
    [ujima.linux.disk      :refer [block-device?]]
    [ujima.linux.shell     :refer [require-root!]]
    [ujima.linux.disk.loop :as loopback]))


(defn attach-loopback! [{:keys [img-file-path readonly]}]
  (require-root!)

  (println
    (loopback/attach-loopback-device! img-file-path readonly))) 


(defn detach-loopback! [{ path :img-file-or-loop-device}]
  (require-root!)
  
  (let [devices  (cond 
                   (block-device? path) [path]
                   :otherwise           (loopback/path->loopback-devices path))]
    
    (doseq [device devices]
      (loopback/detach-loopback-device! device)) 
    
    (println (clojure.string/join "\n" devices))))


(defn list-loopbacks! [_]
  (println
    (->> (loopback/loopback-devices)
      (map #(str (:name %) " -> " (:back-file %)))
      (clojure.string/join "\n"))))
