(ns ujima.ujimactl
  "The runtime invoked ONCE, beside ujimad's long-running face — same code, another entry
   point, reached through /usr/local/bin/ujimactl. The twin of tools.cli: one command tree
   on lib.cli, one -main.

   The migration-family verbs speak EDN on one path — entries on stdin, the report map on
   stdout, stderr only when something actually failed. Their callers are programs: an
   installer chrooting a slot, an upgrade piping one slot's export into another slot's seed.

   Some verbs act on this machine's disk and some belong to the daemon. `migration seed`
   writes a store no daemon owns and can never route anywhere; applying comes in two
   shapes that are NOT interchangeable — see `seed!`."
  (:require [clojure.edn :as edn]
            [lib.cli   :as cli]
            [lib.io    :as io]
            [lib.shell :as shell]
            [ujima.log :as log]
            [ujima.control :as control]
            [ujima.migration.export :as export]
            [ujima.migration.import :as import]
            [ujima.upgrade :as upgrade]))


(defn- init-control! []
  (let [cfg (io/slurp-config "config" "ujimad")]
    (control/init! {:storage          (get-in cfg [:control :storage])
                    :tmp              (get-in cfg [:control :tmp])
                    :converge-targets []})))


(defn export!
  "This machine's settings."
  []
  (init-control!)
  (export/export))


(defn seed!
  "Apply ENTRIES by writing the store DIRECTLY — a slot being installed, or the inactive
   slot during an upgrade.

   Never for a running machine: control converges its targets after every write and this
   process has none, so settings would land on disk with nothing reapplying them and the
   machine would report the new value while behaving by the old. Use `import!` there."
  [entries opts]
  (init-control!)
  (import/import! entries opts))


(defn import!
  "Apply ENTRIES to the RUNNING machine through the daemon that owns its store, so the
   write converges. Not wired yet — POST /api/commands/settings/** is the call this
   becomes; refusing beats falling back to a direct write."
  [_entries _opts]
  (throw (ex-info (str "importing into a running machine needs ujimad and is not wired yet "
                       "— use `seed` for a store no daemon owns")
                  {:type ::not-implemented})))


(defn- stdin-entries []
  (let [entries (edn/read-string (slurp *in*))]
    (when-not (sequential? entries)
      (throw (ex-info "stdin must carry a vector of {:scope :setting :value}" {})))
    entries))


(def command-tree
  {"migration"
   {"export"
    {:usage "Usage: ujimactl migration export            (EDN vector -> stdout)"
     :target (fn [_] (prn (export!)))
     :spec {}}

    "seed"
    {:usage "Usage: ujimactl migration seed [--dry-run]  (EDN vector <- stdin, report -> stdout)"
     :target (fn [{:keys [dry-run]}]
               (prn (seed! (stdin-entries) {:dry-run dry-run})))
     :spec {:dry-run {:coerce :boolean :desc "Report what would apply, write nothing"}}}

    "import"
    {:usage "Usage: ujimactl migration import            (EDN vector <- stdin, report -> stdout)"
     :target (fn [_] (prn (import! (stdin-entries) {})))
     :spec {}}}

   "upgrade"
   {"info"
    {:usage "Usage: ujimactl upgrade info"
     :target (fn [_] (prn (upgrade/info)))
     :spec {}}

    "install"
    {:usage "Usage: ujimactl upgrade install <pack>"
     :target (fn [{:keys [pack]}]
               (let [{:keys [slot]} (cli/run-and-display! (upgrade/install! pack))]
                 (println (str "installed slot " (name slot)))))
     :args [:pack]
     :spec {:pack {:desc "The .pack to write into the inactive slot" :require true}}}

    "migrate"
    {:usage "Usage: ujimactl upgrade migrate             (EDN vector <- stdin, report -> stdout)"
     :target (fn [_] (prn (upgrade/migrate! (stdin-entries))))
     :spec {}}

    "boot"
    {:usage "Usage: ujimactl upgrade boot"
     :target (fn [_] (println (str "try-booting into slot " (name (upgrade/boot!)) "...")))
     :spec {}}

    "commit"
    {:usage "Usage: ujimactl upgrade commit"
     :target (fn [_] (println (str "boot slot committed to " (name (upgrade/commit!)))))
     :spec {}}}})


(defn -main [& args]
  (log/off!)
  (let [env (io/slurp-config "config" "ujimad")]
    (shell/install-remap! (get-in env [:shell :commands] {})))
  (cli/dispatch! command-tree (vec args)))
