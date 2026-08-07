(ns build.scripts
  "The os-script contract, kept beside the scripts it describes: where the repo binds
   (the chroot bind IS the dev rsync stage), which scripts exist (os/<name>/script.clj —
   the dir is the script's identity), and how a name runs — run-args is the whole bb
   invocation tail. Consumed by the two executors — tools.cmd.os (chroot) and
   tools.cmd.dev (live ssh) — which supply only what genuinely differs: which bb, and
   the wrapper around it (chroot argv / ssh string). Neither depends on the other."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]))


(def project-mnt "/ujima-src")


(def ^:private scripts-root "os")


(defn available-scripts []
  (->> (fs/glob scripts-root "*/script.clj")
       (mapv #(str (fs/file-name (fs/parent %))))
       sort vec))


(defn- script-ns
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


(defn- classpath
  "The bb classpath for running scripts against a repo root (chroot bind / device stage)."
  [root]
  (str root "/runtime/src:" root "/os"))


(defn run-args
  "The bb argv tail that runs a script against a repo root — classpath, entry, project
   bind. Everything shell-safe by construction (fixed paths + a require-script!-validated
   name), so an executor may splice it into an argv or str/join it into one ssh string."
  [script root]
  ["--classpath" (classpath root)
   "-x" (str (script-ns script) "/run!")
   "--project" root])
