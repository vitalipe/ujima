;; Dev provisioning script — runs ON a ujima device against its own INSTALLED runtime.
;; Shipped to /tmp by `bb dev upgrade` and run as:
;;
;;   cd /ujima/ujimad && sudo bb -cp src:<m2 jars> /tmp/ujima-upgrade.clj <verb> …
;;
;; Every verb derives its own slot; the host never passes one. Two places computing "the
;; other slot" is two places to get it wrong, and wrong means dd over a live root.

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[babashka.fs :as fs]
         '[ujima.device :as device]
         '[ujima.device.ab :as ab]
         '[ujima.device.ab.autoboot :as autoboot]
         '[ujima.device.ab.autoboot.partitions :as partitions]
         '[ujima.linux.disk.mount :as mount]
         '[ujima.linux.sudo :refer [sudo$! sudo$?]]
         '[lib.shell :as shell])


(def commands-in-chroot "/tmp/ujima-migrate.edn")


(defn- require-disk! []
  (or (device/system->disk)
      (throw (ex-info "this machine has no ujima A/B disk — nothing to upgrade" {}))))


(defn- boot-runtime [] (autoboot/->boot))


(defn- running-slot
  "The slot this machine is RUNNING from — during a trial boot that is NOT :boot-slot,
   which still names the committed one. The `and` matters: after a commit :try-boot-slot is
   cleared while the firmware flag is still set for this boot."
  [info]
  (if (and (ab/in-try-boot? (boot-runtime)) (:try-boot-slot info))
    (:try-boot-slot info)
    (:boot-slot info)))


(defn- other-slot [slot]
  (if (= :a slot) :b :a))


(defn- refuse-in-try-boot!
  "The state you would be writing FROM evaporates on the next reboot. Reboots are cheap."
  []
  (when (ab/in-try-boot? (boot-runtime))
    (throw (ex-info (str "this machine is running a TRIAL boot — commit it or reboot to fall "
                         "back before installing anything")
                    {}))))


(defn- target-slot
  "The slot to write: the one we are not running from."
  [info]
  (refuse-in-try-boot!)
  (let [running (running-slot info)
        target  (other-slot running)]
    (when (= running target)
      (throw (ex-info "refusing to write the slot this machine is running from"
                      {:running running})))
    target))


;; ── verbs ───────────────────────────────────────────────────────────────────

(defn status []
  (let [disk (require-disk!)
        info (ab/ujima-disk-info disk)]
    {:running-slot (running-slot info)
     :in-try-boot? (ab/in-try-boot? (boot-runtime))
     :disk         info}))


(defn install!
  "Write PACK into the inactive slot."
  [pack]
  (shell/require-root!)
  (let [disk   (require-disk!)
        target (target-slot (ab/ujima-disk-info disk))]
    (when-not (fs/exists? pack)
      (throw (ex-info "pack not found on the device" {:pack pack})))
    (println (str "installing into slot " (name target) " (boot + root, ~10.5G)"))
    (ab/install-into-slot! disk pack target)
    (println (str "installed slot " (name target)))
    target))


(defn- chroot-classpath
  "The target slot's own classpath, chroot-relative: src + every baked m2 jar."
  [root-mnt]
  (let [jars (fs/glob (str root-mnt "/ujima/m2") "**.jar")]
    (when (empty? jars)
      (throw (ex-info "target slot has no /ujima/m2 jars — pack built from a stale vendor?"
                      {:root (str root-mnt)})))
    (->> jars
         (map #(str/replace (str %) (str root-mnt) ""))
         (sort)
         (cons "src")
         (str/join ":"))))


(defn- run-migration!
  "One `ujima.migration import` inside the target slot, as its report map. No report at all
   is a failure: a usage line would otherwise parse as a clean empty one, which looks
   exactly like success."
  [root-mnt flags]
  (let [{:keys [out err]}
        (sudo$? chroot [root-mnt] "/bin/sh" "-c"
                ;; --edn always — this call wants data back
                [(str "cd /ujima/ujimad && exec /usr/local/bin/bb"
                      " -cp " (chroot-classpath root-mnt)
                      " -m ujima.migration import --edn " (str/join " " flags) " "
                      commands-in-chroot)])
        output (str out "\n" err)]
    (or (->> (str/split-lines output)
             (keep (fn [line]
                     (when (str/starts-with? (str/trim line) "{")
                       (try
                         (let [v (edn/read-string line)]
                           (when (and (map? v) (contains? v :ok?)) v))
                         (catch Throwable _ nil)))))
             (last))
        (throw (ex-info "the target slot's migration produced no report"
                        {:flags (vec flags) :output output})))))


(defn- migrate-in-chroot!
  "Ask the target what it refuses, drop exactly those, apply the rest. Not all-or-nothing:
   one setting the new version dropped must not cost the wifi credentials that are the way
   back to the machine."
  [root-mnt entries]
  (let [{:keys [errors]} (run-migration! root-mnt ["--validate-only"])
        refused          (set (map :entry errors))]
    (if (empty? refused)
      (assoc (run-migration! root-mnt []) :dropped [])
      (let [kept (vec (remove refused entries))]
        (when (empty? kept)
          (throw (ex-info "the target slot refused EVERY setting — nothing to carry forward"
                          {:errors errors})))
        (fs/with-temp-dir [tmp {:prefix "ujima-migrate-filtered-"}]
          (let [filtered (str (fs/path tmp "commands.edn"))]
            (spit filtered (pr-str kept))
            (sudo$! cp [filtered] (str root-mnt commands-in-chroot))
            (assoc (run-migration! root-mnt []) :dropped errors)))))))


(defn migrate!
  "Carry COMMANDS-FILE into the inactive slot through THAT SLOT's migration ns: mount its
   root, bind its settings dir at /ujima/settings, chroot, import."
  [commands-file]
  (shell/require-root!)

  (let [disk    (require-disk!)
        dev     (:device disk)
        target  (target-slot (ab/ujima-disk-info disk))
        entries (edn/read-string (slurp commands-file))
        {cfg-blk :config :as parts} (partitions/device->partitions-by-name dev)
        {:keys [root]}              (get parts target)
        binds   ["/dev" "/proc" "/sys"]]

    (when-not (and (sequential? entries) (seq entries))
      (throw (ex-info "settings file is not a non-empty vector of entries"
                      {:file commands-file})))

    (println (str "migrating " (count entries) " settings through slot " (name target)
                  "'s own migration ns"))

    (let [{:keys [applied warnings dropped]}
          (mount/with-mounted-ext4 [root-mnt root]
            (mount/with-mounted-ext4 [cfg-mnt cfg-blk]
              (let [slot-cfg (str (fs/path cfg-mnt (name target)))
                    settings (str root-mnt "/ujima/settings")]
                (try
                  (sudo$! mount --bind [slot-cfg] [settings])
                  (doseq [b binds] (sudo$! mount --bind [b] (str root-mnt b)))
                  (sudo$! cp [commands-file] (str root-mnt commands-in-chroot))
                  (migrate-in-chroot! root-mnt entries)
                  (finally
                    (sudo$? rm -f (str root-mnt commands-in-chroot))
                    (doseq [b (reverse binds)]
                      (when (mount/mount-point? (str root-mnt b))
                        (sudo$! umount (str root-mnt b))))
                    (when (mount/mount-point? settings)
                      (sudo$! umount [settings])))))))]

      (doseq [{:keys [entry warning]} warnings]
        (println "  warn    " warning "—" (pr-str entry)))
      (doseq [{:keys [entry error]} dropped]
        (println "  DROPPED " error "—" (pr-str entry)))
      (println (str "  carried " applied " settings forward, dropped " (count dropped)))
      target)))


(defn boot!
  "Arm the inactive slot and reboot into it on trial. Arms here rather than trusting an
   earlier install, so prepared and pointed-at never drift apart."
  []
  (shell/require-root!)
  (let [disk   (require-disk!)
        info   (ab/ujima-disk-info disk)
        target (target-slot info)]

    (when-not (get-in info [:slots target :ujima-os])
      (throw (ex-info (str "slot " (name target) " carries no install record — refusing to "
                           "try-boot into a slot with nothing in it")
                      {:slot target})))

    (ab/set-try-boot-slot! disk target)
    (println (str "try-booting into slot " (name target) "..."))
    (ab/try-boot! (boot-runtime))))


(defn commit!
  "Keep the slot we are running. Unlike the others this must work inside a trial boot."
  []
  (shell/require-root!)
  (let [disk    (require-disk!)
        running (running-slot (ab/ujima-disk-info disk))]
    (ab/set-boot-slot! disk running)
    (println (str "boot slot committed to " (name running)))
    running))


;; ── dispatch ────────────────────────────────────────────────────────────────

(let [[verb & args] *command-line-args*]
  (case verb
    "status"  (prn (status))
    "install" (install! (first args))
    "migrate" (migrate! (first args))
    "boot"    (boot!)
    "commit"  (commit!)
    (do (println (str "usage: ujima-upgrade.clj status\n"
                      "       ujima-upgrade.clj install <pack>\n"
                      "       ujima-upgrade.clj migrate <commands.edn>\n"
                      "       ujima-upgrade.clj boot | commit"))
        (System/exit 2))))
