(ns tools.cmd.pack
  (:require
    [babashka.fs           :as fs]
    [lib.edn               :as edn]
    [ujima.linux.disk      :refer [block-device?]]
    [lib.shell             :refer [require-root!]]
    [ujima.linux.disk.loop :as loopback]
    [ujima.pack            :as pack]))


(defn make!
  "Pack an os image into a .pack. `src` is a 2-partition medium — an .img file
   (loopback-attached) or a block device, detected explicitly."
  [{:keys [src out]}]
  (require-root!)
  (cond
    (block-device? src)
    (pack/pack! src out)

    (fs/regular-file? src)
    (loopback/with-loopback-device [dev src]
      (pack/pack! dev out))

    :else
    (throw (ex-info "pack source must be an .img file or a block device"
                    {:src src}))))


(defn validate-pack! [{:keys [ujima-pack-path]}]
  (pack/validate! ujima-pack-path)
  (println "ok"))


(defn print-pack-meta! [{:keys [ujima-pack-path format]}]
  (let [meta (pack/metadata ujima-pack-path)]
    (println (case format 
               "edn" (pr-str meta)
               "json" (edn/edn->json meta)))))