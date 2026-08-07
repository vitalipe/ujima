(ns build.runner
  "The os-script contract, kept beside the scripts it describes: where the repo binds
   (the chroot bind IS the dev rsync stage), which scripts exist (os/<name>/script.clj —
   the dir is the script's identity), how a name resolves to its entry
   (<name>.script/run!), and the classpath that runs them. Consumed by the two runners —
   tools.cmd.os (chroot) and tools.cmd.dev (live ssh) — so neither depends on the other."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]))


(def project-mnt "/ujima-src")


(def ^:private scripts-root "os")


(defn available-scripts []
  (->> (fs/glob scripts-root "*/script.clj")
       (mapv #(str (fs/file-name (fs/parent %))))
       sort vec))


(defn script-ns
  "\"ujimaify\" -> \"ujimaify.script\", the ns whose run! is the script's entry."
  [script]
  (str (name script) ".script"))


(defn require-script!
  "Throw (listing what's available) if os/<script>/script.clj doesn't exist — fails a
   typo BEFORE the expensive part: root+loopback in cmd.os, a full rsync in cmd.dev."
  [script]
  (when-not (fs/exists? (fs/path scripts-root (name script) "script.clj"))
    (throw (ex-info (str "Unknown script: " script)
                    {:script script :available (available-scripts)}))))


(defn classpath
  "The bb classpath for running scripts against a repo root (chroot bind / device stage)."
  [root]
  (str root "/runtime/src:" root "/os"))
