(ns ujima.linux.disk.block
  "The block device tree as it changes: udev ticks, lsblk answers. Reports removable
   partitions; mounting and all policy live above."
  (:require [clojure.core.async :as async]
            [clojure.java.io    :as java-io]
            [cheshire.core      :as json]
            [babashka.process   :as p]
            [lib.shell          :refer [$?]]
            [ujima.log          :as log]))


(def ^:private settle-ms 800)   ; udev announces a disk and its partitions ~40ms apart


(defn- removable?
  ;; lsblk emits a json boolean, but older builds emit "0"/"1" — and "0" is truthy in clojure
  [rm]
  (or (true? rm) (= 1 rm) (= "1" rm)))


(defn- partitions-of
  "lsblk rows -> {uuid {:uuid :disk :label :fstype :size}}. Removable, type=part, and a
   filesystem uuid to be keyed on. Where a partition is mounted is not identity."
  [rows]
  (into {}
        (for [{:keys [uuid type pkname label fstype size rm]} rows
              :when (and (= "part" type) (removable? rm) uuid)]
          [uuid {:uuid uuid :disk pkname :label label :fstype fstype :size size}])))


(defn- log-skipped!
  "Removable things we deliberately don't report — silence here reads as 'nothing plugged in'."
  [rows]
  (doseq [{:keys [path type uuid fstype rm]} rows
          :when (removable? rm)]
    (cond
      (and (= "part" type) (nil? uuid))
      (log/info "block: partition without a uuid — skipped" {:path path :fstype fstype})

      (and (= "disk" type) fstype)
      (log/info "block: filesystem on a whole device — skipped" {:path path :fstype fstype}))))


(defn partitions
  "Removable partitions by uuid. {} when lsblk can't answer — an unreadable world is 'nothing
   plugged in', never a throw on the listener thread."
  []
  (let [{:keys [ok? out]} ($? lsblk -J -l -o "PATH,PKNAME,TYPE,UUID,LABEL,FSTYPE,SIZE,RM")]
    (if ok?
      (let [rows (:blockdevices (json/parse-string out true))]
        (log-skipped! rows)
        (partitions-of rows))
      (do (log/warn "block: lsblk failed — reporting nothing plugged in") {}))))


(defn watch-partitions!
  "Watch removable partitions (udev block events trigger a re-read) and emit one event per
   change on the returned channel:
     {:partitions {uuid facts}}
   The first emit is a baseline, not arrivals. Pure mechanism — no history, since the
   consumer holding this state is the one that owns diffing it."
  []
  (let [ch   (async/chan (async/sliding-buffer 8))
        ;; :shutdown — the finally below never runs when bb itself is killed
        ;; (session cycle); without it every restart orphans a udevadm monitor
        proc (p/process ["udevadm" "monitor" "--udev" "--subsystem-match=block"]
                        {:out :stream :err :stream :shutdown p/destroy-tree})]

    (async/thread
      (try
        (let [initial (partitions)]
          (async/>!! ch {:partitions initial})
          (with-open [reader (java-io/reader (:out proc))]
            (loop [prv initial]
              (when (.readLine reader)
                ;; one insert is two udev lines (disk, then partition) — settle so the
                ;; partition is enumerated and probed before we look
                (Thread/sleep settle-ms)
                (let [now (partitions)]
                  (if (= now prv)
                    (recur prv)
                    (when (async/>!! ch {:partitions now})
                      (recur now))))))))
        (catch Throwable e
          (log/error "block watch: udev monitor died" {:error (ex-message e)}))
        (finally
          (p/destroy-tree proc))))

    ch))
