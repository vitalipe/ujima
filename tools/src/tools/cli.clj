(ns tools.cli
  (:require
    [ujima.env          :as env]
    [lib.cli            :as cli]
    [tools.cmd.loopback :as loopback]
    [tools.cmd.pack     :as pack]
    [tools.cmd.image    :as image]))



(def command-tree
  {"loopback"
   {"attach"
    {:usage "Usage: tools loopback attach <img-file-path> [--readonly]"
     :target loopback/attach-loopback!
     :args [:img-file-path]
     :spec {:img-file-path {:desc "Image file path"
                            :require true}
            :readonly {:coerce :boolean
                       :desc "Attach image read-only"}}}

    "detach"
    {:usage "Usage: tools loopback detach <img-file-or-loop-device>"
     :target loopback/detach-loopback!
     :args [:img-file-or-loop-device]
     :spec {:img-file-or-loop-device {:desc "Image path or loop device path"
                                      :require true}}}

    "list"
    {:usage "Usage: tools loopback list"
     :target loopback/list-loopbacks!
     :args []
     :spec {}}}

   "pack"
   {"create"
    {:usage "Usage: tools pack create <block-device-path> <ujima-pack-out-path> [--target <target-name>] [--arch <arch-name>]"
     :target pack/create-pack!
     :args [:block-device-path :ujima-pack-out-path]
     :spec {:block-device-path {:desc "Source block device path"
                                :require true}
            :ujima-pack-out-path {:desc "Output Ujima pack path"
                                  :require true}
            :target {:desc "Target name, e.g. rpi"}
            :arch {:desc "Architecture name, e.g. arm64"}}}

    "validate"
    {:usage "Usage: tools pack validate <ujima-pack-path>"
     :target pack/validate-pack!
     :args [:ujima-pack-path]
     :spec {:ujima-pack-path {:desc "Ujima pack path"
                              :require true}}}

    "meta"
    {:usage "Usage: tools pack meta <ujima-pack-path> [--format edn|json]"
     :target pack/print-pack-meta!
     :args [:ujima-pack-path]
     :spec {:ujima-pack-path {:desc "Ujima pack path"
                              :require true}
            :format {:desc "Output format: edn or json"
                     :default "edn"
                     :validate #{"edn" "json"}}}}}

   "image"
   {"fetch"
    {:usage "Usage: tools image fetch <url> <out-img> [--sha256 <hex>]"
     :target image/fetch! :args [:url :out]
     :spec {:url    {:desc "Base image URL" :require true}
            :out    {:desc "Output .img path" :require true}
            :sha256 {:desc "Expected sha256 of the downloaded (compressed) file"}}}

    "customize"
    {:usage "Usage: tools image customize <img>"
     :target image/customize! :args [:img]
     :spec {:img {:desc "Image to open a (no-op) chroot into" :require true}}}

    "pack"
    {:usage "Usage: tools image pack <img> <out-pack>"
     :target image/pack! :args [:img :out]
     :spec {:img {:desc "Customized source image" :require true}
            :out {:desc "Output .pack path" :require true}}}

    "chroot"
    {:usage "Usage: tools image chroot <img>"
     :target image/chroot-shell! :args [:img]
     :spec {:img {:desc "Image to open an interactive chroot into" :require true}}}

    "from-pack"
    {:usage "Usage: tools image from-pack <pack> <out-img> [--layout autoboot]"
     :target image/from-pack! :args [:pack :out]
     :spec {:pack   {:desc "Source .pack" :require true}
            :out    {:desc "Output .img" :require true}
            :layout {:desc "Disk layout" :default "autoboot" :validate #{"autoboot"}}}}

    "run"
    {:usage "Usage: tools image run <img> [--arch arm64]   (EXPERIMENTAL)"
     :target image/run! :args [:img]
     :spec {:img  {:desc "Image to boot in qemu (experimental)" :require true}
            :arch {:desc "Guest arch" :default "arm64"}}}}})

(defn -main
  [& args]

  (env/init! ["config/ujima.edn"
              "config/config.local.edn"])
  (cli/dispatch! command-tree args))
