(ns tools.cli
  (:require
    [ujima.env          :as env]
    [ujima.cli.dispatch :as cli]
    [tools.cli.loopback :as loopback]
    [tools.cli.pack     :as pack]))



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
                     :validate #{"edn" "json"}}}}}})

(defn -main
  [& args]

  (env/init! ["ujima-os/config/ujima.edn"
              "ujima-os/config/config.local.edn"])
  (cli/dispatch! command-tree args))
