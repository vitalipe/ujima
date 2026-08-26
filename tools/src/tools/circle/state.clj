(ns tools.circle.state
  "Where a running dev process records itself, so another invocation can find and stop it.

   The directory has to resolve the same under `sudo` as without it: `sudo` drops
   XDG_RUNTIME_DIR, so reading it blind sends an elevated `down` to /tmp while the process
   that wrote the file used /run/user/<uid>, and the teardown reports nothing to do.
   SUDO_UID names the invoking user, so it answers first."
  (:require [clojure.edn :as edn]
            [babashka.fs :as fs]))


(defn dir []
  (or (when-let [uid (System/getenv "SUDO_UID")]
        (let [d (str "/run/user/" uid)]
          (when (fs/exists? d) d)))
      (System/getenv "XDG_RUNTIME_DIR")
      "/tmp"))


(defn file [name]
  (str (fs/path (dir) name)))


(defn process [pid]
  (some-> pid java.lang.ProcessHandle/of (.orElse nil)))


(defn started-at [ph]
  (some-> ph .info .startInstant (.orElse nil) .toEpochMilli))


(defn ours?
  "Is the claim's process still the one that wrote it? A pid outlives nothing — the number
   is reused — and a dev tool has no business killing, or standing aside for, a stranger
   that inherited it. A claim from before this check carries no start time: take its word."
  [{:keys [pid started]}]
  (when-let [ph (process pid)]
    (and (.isAlive ph)
         (or (nil? started) (= started (started-at ph))))))


(defn me
  "This process, in the shape a claim records it."
  []
  (let [ph (java.lang.ProcessHandle/current)]
    {:pid (.pid ph) :started (started-at ph)}))


(defn read! [f]
  (when (fs/exists? f)
    (try (edn/read-string (slurp f)) (catch Exception _ nil))))


(defn write! [f state]
  (spit f (pr-str state)))


(defn clear! [f]
  (fs/delete-if-exists f))


(defn stop!
  "SIGTERM the claim's process and wait for it to go, up to TIMEOUT-MS."
  [claim timeout-ms]
  (some-> (process (:pid claim)) .destroy)
  (loop [waited 0]
    (when (and (ours? claim) (< waited timeout-ms))
      (Thread/sleep 100)
      (recur (+ waited 100)))))
