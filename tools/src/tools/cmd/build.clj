(ns tools.cmd.build
  "Build artifacts — pure composition of the other nouns:

     build rpi pack  ->  stage -> os apply -> pack make
     build rpi disk  ->  the same, then `disk autoboot from-pack`

   rpi names the PLATFORM (it determines the boot scheme); a new platform is one
   subtree here + a base in stage/targets + a scheme subtree in cmd/disk."
  (:require
    [clojure.string :as str]
    [babashka.fs :as fs]
    [lib.cli :as lib-cli]
    [lib.shell :refer [require-root!]]
    [tools.cmd.stage :as stage]
    [build.image :as image]
    [tools.cmd.pack  :as pack]
    [tools.cmd.disk  :as disk]))


(defn image-facts
  "The platform facts apply! stamps into /ujima/image.edn."
  [platform]
  {:platform platform :base (get stage/targets platform)})


(defn- stage-and-pack!
  "stage -> os apply -> pack make. Returns {:os <img-path> :pack <pack-path>}."
  [{:keys [out dev] :as opts}]
  (require-root!)
  (let [{os-img :out} (stage/stage! "rpi" opts)
        os-img  (if dev
                  (let [d (str/replace os-img #"\.img$" "-dev.img")]
                    (fs/move os-img d {:replace-existing true})
                    d)
                  os-img)
        pack-out (or out (str/replace os-img #"\.img$" ".pack"))]

    (image/apply! {:img os-img :dev dev :image (image-facts "rpi")})

    (println (str "== pack -> " pack-out))
    (pack/make! {:src os-img :out pack-out})
    {:os os-img :pack pack-out}))


(defn- print-outputs! [outputs]
  (println)
  (println "build done:")
  (doseq [f outputs]
    (println " " f)))


(defn build-pack!
  "`out` defaults beside the staged image (branch+sha named)."
  [opts]
  (let [{:keys [os pack] :as result} (stage-and-pack! opts)]
    (print-outputs! [os pack])
    result))


(defn build-disk!
  "The pack, then `disk autoboot from-pack` onto the target."
  [{:keys [target wipe] :as opts}]
  (let [{:keys [os pack]} (stage-and-pack! (dissoc opts :target :wipe))]
    (println (str "== disk -> " target))
    (lib-cli/run-and-display! (disk/from-pack! {:pack pack :target target :wipe wipe}))
    (print-outputs! [os pack target])
    {:os os :pack pack :disk target}))


;; ── the CLI ─────────────────────────────────────────────────────────────────

(def cli
  {"build"
   {"rpi"
    {"pack"
     {:usage "Usage: build rpi pack [<out.pack>] [--dev]"
      :target build-pack!
      :args [:out]
      :spec {:out {:desc "Output .pack path (default: out/ujima-<branch>-<sha>.pack)"}
             :dev {:coerce :boolean
                   :desc "Bake the dev rig (ssh/vnc/xdotool) and skip cleanup"}}}

     "disk"
     {:usage "Usage: build rpi disk <img|blockdev> [--dev] [--wipe]"
      :target build-disk!
      :args [:target]
      :spec {:target {:desc "Disk target: .img file or block device" :require true}
             :dev    {:coerce :boolean
                      :desc "Bake the dev rig (ssh/vnc/xdotool) and skip cleanup"}
             :wipe   {:coerce :boolean
                      :desc "Destroy existing partitions on a block device"}}}}}})
