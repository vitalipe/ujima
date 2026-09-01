(ns ujima.upgrade
  "Install a pack into the slot this machine is NOT running from, carry its settings across,
   and try-boot it.

   No verb takes a slot — it is always the other one. The host CLI picks slots because it
   works on a card in a reader; a machine upgrading itself never should.

   Long verbs return cold flows and print nothing: progress is timeline events, so the same
   call renders on a terminal, streams to the setup button, or runs headless."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]

            [lib.shell :as shell]
            [lib.task.flow :refer [flow <join!]]
            [ujima.linux.sudo       :refer [sudo$! sudo$?]]
            [ujima.linux.disk.mount :as mount]

            [ujima.device    :as device]
            [ujima.device.ab :as ab]
            [ujima.device.ab.autoboot :as autoboot]
            [ujima.device.ab.autoboot.partitions :as partitions]))


(defn- require-disk! []
  (or (device/system->disk)
      (throw (ex-info "this machine has no ujima A/B disk — nothing to upgrade" {}))))


(defn- running-slot
  "The slot this machine is RUNNING from — during a trial boot that is NOT :boot-slot. The
   `and` matters: after a commit :try-boot-slot is cleared while the firmware flag is not."
  [disk-info]
  (if (and (ab/in-try-boot? (autoboot/->boot-runtime)) (:try-boot-slot disk-info))
    (:try-boot-slot disk-info)
    (:boot-slot disk-info)))


(defn- target-slot
  "The slot to write. Refuses inside an UNCOMMITTED trial boot — the state written FROM
   evaporates at the next reboot. The firmware flag alone cannot decide: it stays 1 for
   the whole boot even after a commit, so trial = the same `and` as running-slot."
  [disk-info]
  (when (and (ab/in-try-boot? (autoboot/->boot-runtime)) (:try-boot-slot disk-info))
    (throw (ex-info "this machine is running a TRIAL boot — commit it or reboot to fall back"
                    {})))
  (if (= :a (running-slot disk-info)) :b :a))


(defn info
  "What is running and what each slot holds."
  []
  (let [disk (require-disk!)
        i    (ab/ujima-disk-info disk)]
    {:running-slot (running-slot i)
     :in-try-boot? (ab/in-try-boot? (autoboot/->boot-runtime))
     :disk         i}))


(defn install!
  "Write PACK into the inactive slot. A path either way — pushed by a dev host today, read
   off a share when network storage lands."
  [pack]
  (shell/require-root!)
  (when-not (fs/exists? pack)
    (throw (ex-info (str "pack not found: " pack) {:pack (str pack)})))
  (let [disk   (require-disk!)
        target (target-slot (ab/ujima-disk-info disk))]
    (flow :install
      (<join! 100 (ab/install-into-slot! disk pack target))
      {:slot target :pack (str pack)})))


(defn- unwind-chroot-binds!
  "Best-effort umount of every chroot bind, deepest first. Returns what stayed stuck as
   [{:mount :error}] — never throws, so one busy bind cannot strand the rest."
  [root-mnt settings binds]
  (->> (concat (map #(str root-mnt %) (reverse binds)) [settings])
       (filter mount/mount-point?)
       (keep (fn [m]
               (let [{:keys [ok? err]} (sudo$? umount [m])]
                 (when-not ok? {:mount m :error (str/trim (str err))}))))
       (vec)))


(defn- with-slot-chroot*
  "Mount SLOT's root, mount that slot's OWN config partition where its runtime expects
   settings, bind /dev /proc /sys, call F with the root, and unwind all of it.

   The unwind is best-effort, then LOUD: a mount left pointing into the inactive slot is
   an error naming what stayed stuck, never a lazy umount — deferred teardown on a
   partition we are about to try-boot is hidden state."
  [disk slot f]
  (let [parts                       (partitions/device->partitions-by-name (:device disk))
        {:keys [root] cfg-blk :config} (get parts slot)
        binds                       ["/dev" "/proc" "/sys"]]
    (mount/with-mounted-ext4 [root-mnt root]
      (let [settings (str root-mnt "/ujima/settings")
            result   (try
                       (sudo$! mount -t ext4 [cfg-blk] [settings])
                       (doseq [b binds] (sudo$! mount --bind [b] (str root-mnt b)))
                       {:value (f root-mnt)}
                       (catch Throwable e {:thrown e}))
            stuck    (unwind-chroot-binds! root-mnt settings binds)]

        ;; stderr as well as the throw: a stuck bind also fails the outer root umount,
        ;; and THAT error is the one that propagates — these lines must not be lost
        (when (seq stuck)
          (binding [*out* *err*]
            (doseq [{:keys [mount error]} stuck]
              (println (str "stuck mount: " mount " — " error)))))

        (when-let [e (:thrown result)]
          (throw e))
        (when (seq stuck)
          (throw (ex-info "the chroot unwind left mounts pointing into the inactive slot"
                          {:slot slot :stuck stuck})))
        (:value result)))))


(defmacro ^:private with-slot-chroot [[sym disk slot] & body]
  `(with-slot-chroot* ~disk ~slot (fn [~sym] ~@body)))


(defn migrate!
  "Carry ENTRIES into the inactive slot by letting that slot seed itself — its own ujimactl
   reads them on stdin and answers with a report, so nothing is written into it and the
   entries, secrets and all, never touch its filesystem.

   What it refuses comes back in :dropped rather than failing: these entries came off a
   machine's own export, so a refusal is the registry moving, not a mistake."
  [entries]
  (shell/require-root!)
  (when-not (and (sequential? entries) (seq entries))
    (throw (ex-info "settings to carry must be a non-empty vector" {})))

  (let [disk   (require-disk!)
        target (target-slot (ab/ujima-disk-info disk))]
    (with-slot-chroot [root-mnt disk target]
      (let [{:keys [ok? out err]} (sudo$? {:in (pr-str entries)}
                                          chroot [root-mnt] "/usr/local/bin/ujimactl"
                                          "migration" "seed")
            report (try (edn/read-string (str/trim (str out))) (catch Throwable _ nil))]
        (when-not ok?
          (throw (ex-info "the target slot's seed failed" {:slot target :error (str err out)})))
        (when-not (map? report)
          (throw (ex-info "the target slot's seed answered with no report"
                          {:slot target :out (str out)})))
        (assoc report :slot target)))))


(defn boot!
  "Arm the inactive slot and reboot into it on trial. Arms here rather than trusting an
   earlier install, so prepared and pointed-at never drift apart."
  []
  (shell/require-root!)
  (let [disk   (require-disk!)
        i      (ab/ujima-disk-info disk)
        target (target-slot i)]
    (when-not (get-in i [:slots target :ujima-os])
      (throw (ex-info (str "slot " (name target) " carries no install record — refusing to "
                           "try-boot into a slot with nothing in it")
                      {:slot target})))
    (ab/set-try-boot-slot! disk target)
    (ab/try-boot! (autoboot/->boot-runtime))
    target))


(defn commit!
  "Keep the slot we are running. Unlike the others this must work inside a trial boot."
  []
  (shell/require-root!)
  (let [disk    (require-disk!)
        running (running-slot (ab/ujima-disk-info disk))]
    (ab/set-boot-slot! disk running)
    running))
