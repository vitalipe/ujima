(ns tools.cmd.pin
  "bb pin — pull world-truth into the repo as committed, build-verified pins.
   Each pin's CONSUMER verifies it (the ujimad stage diffs the catalogs, the
   install stage sha-checks the jars, the boot stage matches the kernel):
   pin writes, the build refuses drift."
  (:require [babashka.process :as p]
            [build.schema  :as schema]
            [build.deps    :as deps]
            [tools.cmd.dev :as dev]))


(def ^:private usage
  "bb pin <what> — pull world-truth into the repo as a committed pin:

  schema <rootfs>   tz/xkb catalogs -> runtime/src/schema/build
                    from a MOUNTED IMAGE ROOTFS — never this host: the build
                    diffs the pin against the image
  deps              the bb-deps manifest -> os/build/deps-pin.edn
                    from deps.edn (resolved on the host)
  initramfs <ip>    kernel-matched initramfs -> os/pipeline/boot/initramfs/
                    built natively on a dev Pi (overlay must be disabled:
                    lock-fs disable + reboot first)

Review the diff, commit. The build verifies every pin against the image.")


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


(defn dispatch! [& args]
  (case (first args)
    "schema"    (if-let [root (second args)]
                  (schema/generate! root)
                  (throw (ex-info "bb pin schema <rootfs> — needs a mounted image rootfs" {})))
    "deps"      (deps/pin!)
    "initramfs" (if-let [ip (second args)]
                  (pin-initramfs! ip)
                  (throw (ex-info "bb pin initramfs <ip> — needs the dev Pi's address" {})))
    (println usage)))
