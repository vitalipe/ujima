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


;; ── the CLI ─────────────────────────────────────────────────────────────────

(def cli
  {"pack"
   {"make"
    {:usage "Usage: pack <img|blockdev> <out-pack>"
     :target make!
     :args [:src :out]
     :spec {:src {:desc "Source OS image file or block device" :require true}
            :out {:desc "Output .pack path" :require true}}}

    "validate"
    {:usage "Usage: pack validate <pack>"
     :target validate-pack!
     :args [:ujima-pack-path]
     :spec {:ujima-pack-path {:desc "Ujima pack path" :require true}}}

    "meta"
    {:usage "Usage: pack meta <pack> [--format edn|json]"
     :target print-pack-meta!
     :args [:ujima-pack-path]
     :spec {:ujima-pack-path {:desc "Ujima pack path" :require true}
            :format {:desc "Output format: edn or json"
                     :default "edn"
                     :validate #{"edn" "json"}}}}}})
