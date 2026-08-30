(ns tools.cmd.pin
  "bb pin — pull world-truth into the repo as committed, build-verified pins.
   Each pin's CONSUMER verifies it (the runtime stage diffs the catalogs, the
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


;; ── the CLI ─────────────────────────────────────────────────────────────────

(def cli
  {"pin"
   {"schema"
    {:usage "Usage: pin schema <rootfs>"
     :desc  "tz/xkb catalogs -> runtime/src/schema/build, read from a MOUNTED IMAGE ROOTFS — never this host: the build diffs the pin against the image"
     :target schema!
     :args [:rootfs]
     :spec {:rootfs {:desc "Mounted image rootfs to read the catalogs from" :require true :coerce :string}}}

    "deps"
    {:usage "Usage: pin deps"
     :desc  "the bb-deps manifest -> os/build/deps-pin.edn, resolved from deps.edn on this host"
     :target deps!
     :spec {}}

    "initramfs"
    {:usage "Usage: pin initramfs <ip>"
     :desc  "kernel-matched initramfs -> os/pipeline/boot/initramfs/, built natively on a dev Pi (its overlay must be off: lock-fs disable + reboot first)"
     :target initramfs!
     :args [:ip]
     :spec {:ip {:desc "Dev Pi address" :require true :coerce :string}}}}})
