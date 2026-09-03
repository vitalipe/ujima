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
(defonce ^:private cfg*        (atom {}))   ; :mounts-dir by init!, :targets by on-converge!
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
  "Stat the known names under the ujima/ dir — one stat gates everything, and never
   enumerate, so a stick with 100k files costs the same as an empty one. A
   present-but-unreadable marker still reports, with a nil value."
  [mnt]
  (let [dir (fs/path mnt schema/dir)]
    (if (fs/directory? dir)
      (into {} (for [[filename type] schema/markers
                     :let  [path (fs/path dir filename)]
                     :when (fs/regular-file? path)]
                 [type (marker-value path)]))
      {})))


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


;; rw where repair exists and the semantics are ours; ro where they are not — ntfs3
;; has no real linux fsck (ntfsfix only clears the dirty flag), and an unknown
;; filesystem mounts ro because readable beats invisible.
(def ^:private mount-opts
  {"vfat"  ["rw" "uid=ujima" "gid=ujima" "utf8" "flush" "nosuid" "nodev" "noexec"]
   "exfat" ["rw" "uid=ujima" "gid=ujima" "nosuid" "nodev" "noexec"]
   "ext4"  ["rw" "nosuid" "nodev" "noexec"]
   "ntfs"  ["ro" "nosuid" "nodev" "noexec"]})

(def ^:private default-opts ["ro" "nosuid" "nodev" "noexec"])

(def ^:private fsck-of
  {"vfat"  mount/fsck-vfat!
   "exfat" mount/fsck-exfat!
   "ext4"  mount/fsck-ext4!})


(defn- ->mount-task [fstype uuid mnt]
  (flow :storage/mount
    
    (fs/create-dirs mnt)
    
    (mount/umount-lazy! mnt)
    (when-let [fsck! (fsck-of fstype)]
      (let [r (fsck! (str "/dev/disk/by-uuid/" uuid))]
        (when-not (:ok? r)
          (log/warn "storage: fsck did not come back clean" {:uuid uuid :fstype fstype}))))
    (mount/mount! fstype {:UUID uuid} mnt (mount-opts fstype default-opts))

    ;; departed while we were mounting: observe! released the point before we took it
    (if (@partitions* uuid)
      {:mount mnt :tokens (sweep mnt)}
      (do (release-mount! mnt) nil))))


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


(defn init! [{:keys [mounts-dir]}]
  (let [mounts-dir (or mounts-dir default-mounts-dir)]
    (reset! partitions* {})
    (reset! prev*       nil)
    (reset! cfg*        {:mounts-dir mounts-dir :targets []})
    (release-all-mounts! mounts-dir)
    nil))


(defn on-converge!
  "Attach a target: F gets (next, prev) after every pass, under the lock."
  [f]
  (swap! cfg* update :targets conj f)
  nil)


(defn snapshot [] (or @prev* []))
