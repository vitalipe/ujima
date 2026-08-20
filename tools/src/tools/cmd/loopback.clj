(ns tools.cmd.loopback
  (:require
    [ujima.linux.disk      :refer [block-device?]]
    [lib.shell             :refer [require-root!]]
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


;; ── the CLI ─────────────────────────────────────────────────────────────────

(def cli
  {"loopback"
   {"attach"
    {:usage "Usage: loopback attach <img-file-path> [--readonly]"
     :target attach-loopback!
     :args [:img-file-path]
     :spec {:img-file-path {:desc "Image file path" :require true}
            :readonly {:coerce :boolean :desc "Attach image read-only"}}}

    "detach"
    {:usage "Usage: loopback detach <img-file-or-loop-device>"
     :target detach-loopback!
     :args [:img-file-or-loop-device]
     :spec {:img-file-or-loop-device {:desc "Image path or loop device path" :require true}}}

    "list"
    {:usage "Usage: loopback list"
     :target list-loopbacks!
     :args []
     :spec {}}}})
