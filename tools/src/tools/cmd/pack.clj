(ns tools.cmd.pack
  (:require
    [lib.edn               :as edn]
    [ujima.linux.disk      :refer [require-block-device!]]
    [ujima.linux.shell     :refer [require-root!]]
    [ujima.linux.disk.loop :as loopback]
    [ujima.pack            :as pack]))


(defn pack-device! [{:keys [device out target arch] :or {target "mock" arch "test"}}]
  (require-root!)
  (pack/pack! device out {:target target :arch arch}))


(defn pack-image! [{:keys [img out target arch] :or {target "mock" arch "test"}}]
  (require-root!)
  (loopback/with-loopback-device [dev img]
    (pack/pack! dev out {:target target :arch arch})))


(defn validate-pack! [{:keys [ujima-pack-path]}]
  (pack/validate! ujima-pack-path)
  (println "ok"))


(defn print-pack-meta! [{:keys [ujima-pack-path format]}]
  (let [meta (pack/metadata ujima-pack-path)]
    (println (case format 
               "edn" (pr-str meta)
               "json" (edn/edn->json meta)))))