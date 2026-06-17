(ns tools.cli.image
  (:require [tools.image :as image]))


(defn fetch!     [opts] (image/fetch! opts))
(defn customize! [opts] (image/customize! opts))
(defn pack!      [opts] (image/pack! opts))
(defn chroot!    [opts] (image/chroot-shell! opts))
(defn from-pack! [opts] (image/from-pack! opts))
(defn run!       [opts] (image/run! opts))


(def command-tree
  {"fetch"
   {:usage "Usage: tools image fetch <url> <out-img> [--sha256 <hex>]"
    :target fetch! :args [:url :out]
    :spec {:url    {:desc "Base image URL" :require true}
           :out    {:desc "Output .img path" :require true}
           :sha256 {:desc "Expected sha256 of the downloaded (compressed) file"}}}

   "customize"
   {:usage "Usage: tools image customize <img>"
    :target customize! :args [:img]
    :spec {:img {:desc "Image to open a (no-op) chroot into" :require true}}}

   "pack"
   {:usage "Usage: tools image pack <img> <out-pack>"
    :target pack! :args [:img :out]
    :spec {:img {:desc "Customized source image" :require true}
           :out {:desc "Output .pack path" :require true}}}

   "chroot"
   {:usage "Usage: tools image chroot <img>"
    :target chroot! :args [:img]
    :spec {:img {:desc "Image to open an interactive chroot into" :require true}}}

   "from-pack"
   {:usage "Usage: tools image from-pack <pack> <out-img> [--layout autoboot]"
    :target from-pack! :args [:pack :out]
    :spec {:pack   {:desc "Source .pack" :require true}
           :out    {:desc "Output .img" :require true}
           :layout {:desc "Disk layout" :default "autoboot" :validate #{"autoboot"}}}}

   "run"
   {:usage "Usage: tools image run <img> [--arch arm64]   (EXPERIMENTAL)"
    :target run! :args [:img]
    :spec {:img  {:desc "Image to boot in qemu (experimental)" :require true}
           :arch {:desc "Guest arch" :default "arm64"}}}})
