(ns e2e.runner
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [ujima.env :as env]))


(def e2e-root "e2e/tests")


(defn- usage! []
  (binding [*out* *err*]
    (println "Usage:")
    (println "  bb e2e <test-name> [args...]")
    (println "  bb e2e all [args...]")
    (println)
    (println "Examples:")
    (println "  bb e2e http")
    (println "  bb e2e http --keep")
    (println "  bb e2e all"))
  (System/exit 2))


(defn- test-name->ns [test-name]
  (symbol (str "e2e.tests." test-name)))


(defn- test-name->file [test-name]
  (fs/path e2e-root
           (str (str/replace test-name "-" "_") ".clj")))


(defn- file->test-name [file]
  (-> (fs/file-name file)
      (str/replace #"\.clj$" "")
      (str/replace "_" "-")))


(defn- all-test-names []
  (->> (fs/list-dir e2e-root)
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
        (ex-info "E2E test file not found"
                 {:test-name test-name
                  :expected-file (str test-file)})))

    (require test-ns)
    test-ns))


(defn- resolve-test-fn! [test-ns]
  (or (some-> (ns-resolve test-ns 'run!) deref)
      (throw
        (ex-info "E2E test namespace does not define test!"
                 {:namespace test-ns
                  :expected-var (symbol (str test-ns) "test!")}))))


(defn- ctx [test-name tmp-dir args]
  {:test-name test-name
   :test-root e2e-root
   :tmp       tmp-dir
   :args      args})


(defn run-test! [test-name tmp-dir args]
  (let [test-ns (require-test-ns! test-name)
        test!   (resolve-test-fn! test-ns)]
      
      (fs/create-dirs tmp-dir)
      (test! (ctx test-name tmp-dir args))))
    

(defn- run-test-with-tmp! [test-name args]
  (fs/with-temp-dir [tmp-dir {:prefix (str "e2e-" test-name)}]
    (run-test! test-name tmp-dir args)))
    

(defn- run-one-result [test-name args]
  (try
    (if (run-test-with-tmp! test-name args)
      (do
        (println) 
        (println "E2E passed:" test-name) 
        {:test-name test-name :ok? true})
      
      (do
        (println)
        (println "E2E failed:" test-name) 
        {:test-name test-name :ok? false}))

    (catch Throwable e
      (binding [*out* *err*]
        (println)
        (println "E2E failed:" test-name)
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
    (println "E2E summary")
    (println "-----------")
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
        (ex-info "No e2e tests found"
                 {:test-root e2e-root})))

    (println "Running all e2e tests:" (count test-names))

    (let [results (mapv #(run-one-result % args) test-names)
          failed? (some (comp not :ok?) results)]
      (print-summary! results)
      (System/exit (if failed? 1 0)))))


(defn -main [& args]
  (env/init! ["ujima-os/config/ujima.edn"
              "ujima-os/config/config.local.edn"])

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
          (println "E2E runner failed")
          (println (.getMessage e))
          (when-let [data (ex-data e)]
            (prn data))
          (println)
          (.printStackTrace e))
        (System/exit 1)))))
