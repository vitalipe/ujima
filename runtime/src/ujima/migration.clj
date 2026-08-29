(ns ujima.migration
  "Settings migration as a CLI: `export` writes a command file, `import` applies one.
   The halves are ujima.migration.export / ujima.migration.import.

   Two ends because the exporting and importing versions differ: an upgrade runs the NEW
   slot's import over the old slot's export, and the command file is the contract."
  (:require [lib.io :as io]
            [ujima.control :as control]
            [ujima.migration.export :refer [export]]
            [ujima.migration.import :refer [import!]]))


(def ^:private usage
  (str "usage: bb -m ujima.migration export [<out.edn>]\n"
       "       bb -m ujima.migration import [--validate-only] [--edn] <commands.edn>"))


(defn- init-control! []
  (let [cfg (io/slurp-config "config" "ujimad")]
    (control/init! {:storage          (get-in cfg [:control :storage])
                    :tmp              (get-in cfg [:control :tmp])
                    :converge-targets []})))


(defn- run-export! [[out & extra]]
  (when (seq extra)
    (println usage)
    (System/exit 2))
  (init-control!)
  (let [entries (export)]
    (if out
      (do (io/spit-edn! out entries)
          (println (str "exported " (count entries) " settings -> " out)))
      (prn entries))))


(defn- run-import! [args]
  (let [validate-only? (boolean (some #{"--validate-only"} args))
        ;; --edn returns import!'s own map, so a caller can act on WHICH entries were
        ;; refused instead of scraping them out of the human lines
        edn?           (boolean (some #{"--edn"} args))
        [file & extra] (remove #{"--validate-only" "--edn"} args)]

    (when (or (nil? file) (seq extra))
      (println usage)
      (System/exit 2))

    (let [entries (io/slurp-edn file ::unreadable)]
      (when (= ::unreadable entries)
        (println "cannot read" file)
        (System/exit 2))

      (init-control!)

      (let [{:keys [ok? errors warnings applied] :as result}
            (import! entries {:validate-only validate-only?})]

        (if edn?
          (prn result)
          (do
            (doseq [{:keys [entry warning]} warnings]
              (println "WARN: " warning "—" (pr-str entry)))
            (doseq [{:keys [entry error]} errors]
              (println "ERROR:" error "—" (pr-str entry)))
            (println (if ok?
                       (if validate-only? "valid" (str "applied " applied " settings"))
                       "nothing applied"))))

        (when-not ok?
          (System/exit 1))))))


(defn -main [& [verb & args]]
  (case verb
    "export" (run-export! args)
    "import" (run-import! args)
    (do (println usage)
        (System/exit 2))))
