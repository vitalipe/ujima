(ns tools.cmd.build
  "The whole pipeline as one command — the executable statement of the build order:
   vendor (cached) -> stage -> boot -> base -> ujimad -> desktop -> ujimaify ->
   [dev | cleanup] -> pack -> A/B disk (create + slot A from-pack + activate).

   The vendor cache (stage/vendor) is READ here and built only when absent
   (tools.cmd.stage, temp-then-atomic-move) — no code path in build deletes or
   overwrites an existing vendor; rebuilding stays the manual documented rm."
  (:require
    [clojure.string :as str]
    [babashka.fs :as fs]
    [lib.shell :refer [require-root!]]
    [tools.cmd.stage :as stage]
    [tools.cmd.os :as os]
    [tools.cmd.pack  :as pack]
    [tools.cmd.disk  :as disk]))


;; per-target build facts; a new target (debian-uefi …) is one entry here + one in
;; stage/targets + its scheme in cmd/disk
(def ^:private target-info
  {"rpi-os" {:arch "arm64" :scheme "autoboot"}})


(defn build!
  "Run the full pipeline for `target`. --dev bakes the dev rig (ssh/vnc) and skips
   cleanup; the default is a release artifact. Outputs auto-name beside the staged
   image: <name>.img, <name>.pack, <name>-disk.img."
  [{:keys [target dev] :as opts}]
  (let [{:keys [arch scheme]}
        (or (get target-info target)
            (throw (ex-info "Unknown build target"
                            {:expected (set (keys target-info)) :actual target})))

        _ (require-root!)
        {os-img :out} (stage/stage! target opts)
        os-img  (if dev
                  (let [d (str/replace os-img #"\.img$" "-dev.img")]
                    (fs/move os-img d {:replace-existing true})
                    d)
                  os-img)
        pack-out (str/replace os-img #"\.img$" ".pack")
        disk-out (str/replace os-img #"\.img$" "-disk.img")]

    (os/build! {:img os-img :dev dev})

    (println (str "== pack -> " pack-out))
    (pack/make! {:src os-img :out pack-out :target target :arch arch})

    (println (str "== disk -> " disk-out))
    (disk/ab-create! {:scheme scheme :target disk-out})
    (disk/slot! {:slot "a" :verb "from-pack" :a pack-out :b disk-out})
    (disk/slot! {:slot "a" :verb "activate" :a disk-out})

    (println)
    (println "build done:")
    (doseq [f [os-img pack-out disk-out]]
      (println " " f))
    {:os os-img :pack pack-out :disk disk-out}))
