(ns integration.runner
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [lib.io :as io]
            [lib.shell :as shell]))


(def tests-root "runtime/test/integration/tests")


(defn- usage! []
  (binding [*out* *err*]
    (println "Usage:")
    (println "  bb test:integration <test-name> [args...]")
    (println "  bb test:integration all [args...]")
    (println)
    (println "Examples:")
    (println "  bb test:integration ab-disk")
    (println "  bb test:integration all"))
  (System/exit 2))


(defn- test-name->ns [test-name]
  (symbol (str "integration.tests." test-name)))


(defn- test-name->file [test-name]
  (fs/path tests-root
           (str (str/replace test-name "-" "_") ".clj")))


(defn- file->test-name [file]
  (-> (fs/file-name file)
      (str/replace #"\.clj$" "")
      (str/replace "_" "-")))


(defn- all-test-names []
  (->> (fs/list-dir tests-root)
       (filter fs/regular-file?)
       (filter #(str/ends-with? (fs/file-name %) ".clj"))
       (map file->test-name)
       sort
       (into [])))


(defn- require-test-ns! [test-name]
  (let [test-file (test-name->file test-name)
        test-ns   (test-name->ns test-name)]
    (when-not (fs/regular-file? test-file)
      (throw
        (ex-info "Integration test file not found"
                 {:test-name test-name
                  :expected-file (str test-file)})))

    (require test-ns)
    test-ns))


(defn- resolve-test-fn! [test-ns]
  (or (some-> (ns-resolve test-ns 'run!) deref)
      (throw
        (ex-info "Integration test namespace does not define run!"
                 {:namespace test-ns
                  :expected-var (symbol (str test-ns) "run!")}))))


(defn- ctx [test-name tmp-dir args]
  {:test-name test-name
   :test-root tests-root
   :tmp       tmp-dir
   :args      args})


(defn run-test! [test-name tmp-dir args]
  (let [test-ns (require-test-ns! test-name)
        test!   (resolve-test-fn! test-ns)]

      (fs/create-dirs tmp-dir)
      (test! (ctx test-name tmp-dir args))))


(defn- run-test-with-tmp! [test-name args]
  (fs/with-temp-dir [tmp-dir {:prefix (str "integration-" test-name)}]
    (run-test! test-name tmp-dir args)))


(defn- run-one-result [test-name args]
  (try
    (if (run-test-with-tmp! test-name args)
      (do
        (println)
        (println "Integration passed:" test-name)
        {:test-name test-name :ok? true})

      (do
        (println)
        (println "Integration failed:" test-name)
        {:test-name test-name :ok? false}))

    (catch Throwable e
      (binding [*out* *err*]
        (println)
        (println "Integration failed:" test-name)
        (println (.getMessage e))
        (when-let [data (ex-data e)]
          (prn data))
        (println)
        (.printStackTrace e))

      {:test-name test-name
       :ok? false
       :error e})))


(defn- print-summary! [results]
  (let [passed (filter :ok? results)
        failed (remove :ok? results)]
    (println)
    (println "Integration summary")
    (println "-------------------")
    (println "Passed:" (count passed))
    (println "Failed:" (count failed))

    (when (seq failed)
      (println)
      (println "Failed tests:")
      (doseq [{:keys [test-name]} failed]
        (println " -" test-name)))))


(defn- run-all! [args]
  (let [test-names (all-test-names)]
    (when (empty? test-names)
      (throw
        (ex-info "No integration tests found"
                 {:test-root tests-root})))

    (println "Running all integration tests:" (count test-names))

    (let [results (mapv #(run-one-result % args) test-names)
          failed? (some (comp not :ok?) results)]
      (print-summary! results)
      (System/exit (if failed? 1 0)))))


(defn -main [& args]
  (println "Integration env:")
  (prn (io/slurp-config "runtime/config" "ujimad"))
  (println)

  ;; host command remaps (e2fsck/resize2fs from tools[.local].edn): the disk ops run on the host,
  ;; so they need the vendored e2fsprogs the build tools use, not the older system one.
  (shell/install-remap! (get-in (io/slurp-config "tools/config" "tools") [:shell :commands] {}))

  (let [[cmd & test-args] args]
    (when-not cmd
      (usage!))

    (try
      (if (= cmd "all")
        (run-all! test-args)
        (let [result (run-one-result cmd test-args)]
          (System/exit (if (:ok? result) 0 1))))

      (catch Throwable e
        (binding [*out* *err*]
          (println "Integration runner failed")
          (println (.getMessage e))
          (when-let [data (ex-data e)]
            (prn data))
          (println)
          (.printStackTrace e))
        (System/exit 1)))))
