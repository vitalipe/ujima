(ns tools.cmd.pin
  "bb pin — pull world-truth into the repo as committed, build-verified pins.
   Each pin's CONSUMER verifies it (the ujimad stage diffs the catalogs, the
   install stage sha-checks the jars, the boot stage matches the kernel):
   pin writes, the build refuses drift."
  (:require [babashka.process :as p]
            [build.schema  :as schema]
            [build.deps    :as deps]
            [tools.cmd.dev :as dev]))


(defn- pin-initramfs!
  "Run the dev kit's build-initramfs on the Pi (it bails loudly if the overlay
   is still on), then pull the version-named results into the repo."
  [ip]
  (let [t (dev/ssh-transport {:ip ip :user "ujima" :password "ujima" :port "22"})]
    (dev/remote-exec! t "sudo /ujima/dev/build-initramfs")
    (p/shell "sshpass" "-p" (:password t) "scp" "-r" "-P" "22"
             "-o" "StrictHostKeyChecking=no" "-o" "UserKnownHostsFile=/dev/null"
             (str (:host t) ":/ujima/dev/initramfs-out/*")
             "os/pipeline/boot/initramfs/")
    (println "pulled -> os/pipeline/boot/initramfs/")))


(defn schema!    [{:keys [rootfs]}] (schema/generate! rootfs))
(defn deps!      [_]               (deps/pin!))
(defn initramfs! [{:keys [ip]}]    (pin-initramfs! ip))
