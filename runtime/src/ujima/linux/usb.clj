(ns ujima.linux.usb
  "USB storage as a pure event source: watch the mounted-media set and emit one
   event per change. Consumers own all policy — what's ON the storage is not
   this namespace's business."
  (:require [clojure.core.async :as async]
            [clojure.java.io    :as java-io]
            [babashka.fs        :as fs]
            [babashka.process   :as p]
            [ujima.log          :as log]))


(defn- media-mounts
  ;; automounted media land under /media/<user>/<label>
  []
  (into #{} (map str) (fs/glob "/media" "*/*")))


(defn watch-storage!
  "Watch mounted usb storage (udev block events trigger a re-read of /media) and
   emit one event per mount-set change on the returned channel:
     {:before #{<mount-path>} :mounts #{<mount-path>}}
   The first observation emits with :before = :mounts — a baseline, not
   arrivals. Pure mechanism — consumers own all policy; their actions must be
   idempotent (block noise that doesn't change the mount set is deduped here,
   but a mount change without a policy-relevant change still reaches them)."
  []
  (let [ch   (async/chan (async/sliding-buffer 8))
        ;; :shutdown — the finally below never runs when bb itself is killed
        ;; (session cycle); without it every restart orphans a udevadm monitor
        proc (p/process ["udevadm" "monitor" "--udev" "--subsystem-match=block"]
                        {:out :stream :err :stream :shutdown p/destroy-tree})]

    (async/thread
      (try
        (let [initial (media-mounts)]
          (async/>!! ch {:before initial :mounts initial})
          (with-open [reader (java-io/reader (:out proc))]
            (loop [prv initial]
              (when (.readLine reader)
                ;; the mount may not be ready at exact udev event time — a small
                ;; settle lets udisks/systemd automount finish
                (Thread/sleep 800)
                (let [now (media-mounts)]
                  (if (= now prv)
                    (recur prv)
                    (when (async/>!! ch {:before prv :mounts now})
                      (recur now))))))))
        (catch Throwable e
          (log/error "usb watch: udev monitor died" {:error (ex-message e)}))
        (finally
          (p/destroy-tree proc))))

    ch))
