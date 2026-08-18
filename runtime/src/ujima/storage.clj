(ns ujima.storage
  "Removable partitions as observed state. Two threads write — the event listener, and a
   mount that just finished — so a pass runs whole under one lock: fold in what was
   observed, start what is not started, push. The mount itself runs outside the lock, so
   the critical section is a swap and a derive, never the 40ms of mounting.

   Storage sweeps for markers and reports what it finds; it never interprets them."
  (:require [clojure.core.async     :as async]
            [babashka.fs            :as fs]
            [cheshire.core          :as json]
            [lib.io                 :as io]
            [lib.task               :as task]
            [lib.task.flow          :refer [flow]]
            [lib.task.timeline      :as timeline]
            [schema.ujima.storage   :as schema]
            [ujima.log              :as log]
            [ujima.linux.disk.mount :as mount]))


(def ^:private max-marker-bytes 65536)
(def ^:private default-mounts-dir "/ujima/run/storage")


(defonce ^:private partitions* (atom {}))   ; uuid -> {:facts f :task t}
(defonce ^:private prev*       (atom nil))  ; last projection, the prev of (next prev)
(defonce ^:private cfg*        (atom {}))   ; :mounts-dir :targets — set once by init!
(defonce ^:private lock        (Object.))   ; every write path holds this


(defn- mount-of [uuid] (str (fs/path (:mounts-dir @cfg*) uuid)))


(defn- release-mount!
  "Give a mount point back: lazy so it works with the device already gone."
  [mnt]
  (try
    (mount/umount-lazy! mnt)
    (fs/delete-if-exists mnt)
    (catch Throwable e
      (log/warn "storage: could not release mount" {:mount mnt :error (ex-message e)}))))


;; --- markers (read once, here, where the size is bounded) -------------------

(defn- marker-value [path]
  (try
    (if (<= (fs/size path) max-marker-bytes)
      (json/parse-string (io/slurp-text (str path) nil) true)
      (do (log/warn "storage: marker too large — value ignored" {:path (str path)}) nil))
    (catch Throwable e
      (log/warn "storage: unreadable marker" {:path (str path) :error (ex-message e)})
      nil)))


(defn- sweep
  "Stat the known names — never enumerate, so a stick with 100k files costs the same as an
   empty one. A present-but-unreadable marker still reports, with a nil value."
  [mnt]
  (vec (for [[filename type] schema/markers
             :let  [path (fs/path mnt filename)]
             :when (fs/regular-file? path)]
         {:type type :value (marker-value path)})))


;; --- state derivation -------------------------------------------------------

(defn- task->state [t]
  (case (some-> t task/task->state)
    nil             :detected
    (:new :running) :mounting   ; created or claimed — either way it is in flight
    :done           :mounted
    :invalid))


(defn- task-result [t]
  (:payload (timeline/timeline->last-of (task/task->timeline t) (:id t))))


(defn- ->entry [{:keys [facts task]}]
  (let [state (task->state task)]
    (case state
      :mounted (merge facts {:state state} (task-result task))
      :invalid (let [{:keys [error message]} (task-result task)]
                 (assoc facts :state state :reason (or (ex-message error) message)))
      (assoc facts :state state))))


(defn- projection [] (mapv (comp ->entry val) (sort-by key @partitions*)))


;; --- the write path (serialized) --------------------------------------------

(declare converge!)


(defn- ->mount-task [fstype uuid mnt]
  (flow :storage/mount
    (fs/create-dirs mnt)
    (mount/umount-lazy! mnt)
    (mount/mount! fstype {:UUID uuid} mnt ["ro" "nosuid" "nodev" "noexec"])
    {:mount mnt :tokens (sweep mnt)}))


(defn- start-mount! [uuid {:keys [fstype]}]
  (let [t (->mount-task fstype uuid (mount-of uuid))]
    (swap! partitions* assoc-in [uuid :task] t)

    (async/thread
      (task/run!! t)
      (locking lock (converge!)))))


(defn- observe! [partitions]
  (doseq [uuid (remove (set (keys partitions)) (keys @partitions*))]
    (release-mount! (mount-of uuid)))
  (swap! partitions*
         (fn [prev]
           (into {} (for [[uuid facts] partitions]
                      [uuid {:facts facts :task (get-in prev [uuid :task])}])))))


(defn- act! []
  (doseq [[uuid {:keys [facts task]}] @partitions*
          :when (= :detected (task->state task))]
    (start-mount! uuid facts)))


(defn- converge! []
  (let [next (projection)
        prev @prev*]
    (reset! prev* next)
    (doseq [t (:targets @cfg*)] (t next prev))))


(defn handle-event!
  "One pass, whole, under the lock: `act!`'s check-then-act and `converge!`'s
   read-then-reset of prev are only guards while a pass cannot interleave."
  [{:keys [partitions]}]
  (locking lock
    (observe! partitions)
    (act!)
    (converge!)))


;; --- wiring + read side -----------------------------------------------------

(defn- release-all-mounts!
  "EVERY mount under MOUNTS-DIR, no questions asked: init! runs it before this run has
   mounted anything, so what is there came from a previous one — and a partition that left
   while ujimad was down has no entry and no task, so nothing else can ever reap it."
  [mounts-dir]
  (let [mounts (when (fs/directory? mounts-dir) (fs/list-dir mounts-dir))]
    (when (seq mounts)
      (log/info "storage: releasing mounts from a previous run" {:count (count mounts)})
      (doseq [m mounts] (release-mount! (str m))))))


(defn init! [{:keys [mounts-dir converge-targets]}]
  (let [mounts-dir (or mounts-dir default-mounts-dir)]
    (reset! partitions* {})
    (reset! prev*       nil)
    (reset! cfg*        {:mounts-dir mounts-dir
                         :targets    (vec converge-targets)})
    (release-all-mounts! mounts-dir)
    nil))


(defn snapshot [] (or @prev* []))
