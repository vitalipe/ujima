(ns ujima.tools.cli.pack
  (:require
    [ujima.edn             :as edn]
    [ujima.linux.disk      :refer [require-block-device!]]
    [ujima.linux.shell     :refer [require-root-or-passwordless-sudo!]]
    [ujima.deploy.pack     :as pack]))


(defn create-pack! [{:keys [block-device-path ujima-pack-out-path target arch] :or {target "mock" arch "test"}}]
  (pack/pack! block-device-path 
              ujima-pack-out-path 
              {:target target :arch arch}))


(defn validate-pack! [{:keys [ujima-pack-path]}]
  (pack/validate! ujima-pack-path)
  (println "ok"))


(defn print-pack-meta! [{:keys [ujima-pack-path format]}]
  (let [meta (pack/metadata ujima-pack-path)]
    (println (case format 
               "edn" (pr-str meta)
               "json" (edn/edn->json meta)))))