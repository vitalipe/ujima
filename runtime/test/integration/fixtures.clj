(ns integration.fixtures
  "Synthetic .pack artifacts for the integration suite.

   A pack is manifest.edn + boot.img + root.img in a zstd tarball. Nothing in the install path
   boots or inspects OS content, so the images only have to satisfy what that path actually
   touches: validate!'s three entries, a dd into the slot partitions, e2fsck + resize2fs on root,
   and a mounted /etc/passwd for the per-slot settings chown (autoboot/rootfs-owner). Boot needs
   no content at all — cmdline! writes cmdline.txt itself.

   So the whole fixture is an empty vfat plus an ext4 holding one file. mkfs.vfat -C and mke2fs -d
   both build images without mounting them, so this needs no root — only the test does, for the
   loop device."

  (:require [babashka.fs :as fs]

            [lib.io     :refer [spit-edn!]]
            [lib.shell  :refer [$!]]
            [ujima.pack :as pack]))


(def passwd
  ;; the only rootfs content the install path reads: rootfs-owner looks up ujima's uid:gid here
  (str "root:x:0:0:root:/root:/bin/bash\n"
       "ujima:x:1000:1000:Ujima:/home/ujima:/bin/bash\n"))


(def image-facts
  ;; frozen — the manifest's :image for a synthetic pack
  {:version  "v0.0.0-fixture"
   :platform "rpi"
   :built-at "2026-01-01T00:00:00Z"
   :dev      false
   :base     {:url "fixture" :sha256 "fixture"}})


(defn pack!
  "Write a minimal valid Ujima pack to `path`, and return `path`."
  [path]
  (fs/with-temp-dir [work {:prefix "integration-fixture-"}]
    (let [root-tree  (fs/path work "root")
          boot-image (fs/path work "boot.img")
          root-image (fs/path work "root.img")]

      (fs/create-dirs (fs/path root-tree "etc"))
      (spit (str (fs/path root-tree "etc/passwd")) passwd)

      ;; deliberately tiny — both are dd'd into far larger slot partitions (512 MiB boot,
      ;; 10 GiB root) and install grows root to fill its partition with resize2fs
      ($! :mkfs.vfat -C [boot-image] 32768)  ;; 1 KiB blocks -> 32 MiB
      ($! mke2fs -q -t "ext4" -d [root-tree] -F [root-image] "128M")

      ;; frozen :packed-at; the member entries are computed, so they stay honest
      (spit-edn! (fs/path work pack/manifest-member)
                 {:pack-version pack/pack-version
                  :packed-at    "2026-01-01T00:00:00Z"
                  :image        image-facts
                  :members      {:boot (pack/member-entry "boot.img" boot-image)
                                 :root (pack/member-entry "root.img" root-image)}})

      ($! tar --zstd -cf [path] -C [work] [pack/manifest-member] "boot.img" "root.img")))
  path)
