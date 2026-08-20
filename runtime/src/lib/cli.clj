(ns lib.cli
  "Small command-tree wrapper around babashka.cli.

   Defines a nested command map and turns it into a babashka.cli dispatch table.
   Nesting is arbitrary: a node with a :target is a command, anything else groups
   commands, so `noun verb` and `noun sub verb` are both expressible.

   Example:

     (ns my.tools
       (:require [lib.cli :as cli]))

     (defn hello!
       [{:keys [name loud]}]
       (println
         (cond-> (str \"hello \" name)
           loud clojure.string/upper-case)))

     (def command-tree
       {\"greet\"
        {\"hello\"
         {:usage \"Usage: tools greet hello <name> [--loud]\"
          :target hello!
          :args [:name]
          :spec {:name {:desc \"Name to greet\"
                        :require true}
                 :loud {:desc \"Print in uppercase\"
                        :coerce :boolean}}}}})

     (cli/dispatch! command-tree args))

   Supported command entry keys:

     :usage   Command usage string.
     :target  Function called with parsed opts.
     :args    Positional argument names, passed to babashka.cli as :args->opts.
     :spec    babashka.cli option spec.
     :coerce  Optional babashka.cli coercion map.

   Example invocation:

     your-script greet hello world --loud"
  (:require
    [babashka.cli :as cli]
    [clojure.string :as str]
    [lib.task :as task]
    [lib.task.timeline :as timeline]))

;; ----------------------------------------------------------------------------
;; Errors
;; ----------------------------------------------------------------------------

(defn user-error!
  [message data]
  (throw
    (ex-info message
             (assoc data :type :lib.cli/user-error))))

(defn babashka-cli-error?
  [e]
  (= :org.babashka/cli (:type (ex-data e))))

(defn input-exhausted-error?
  [e]
  (and (babashka-cli-error? e)
       (= :input-exhausted (:cause (ex-data e)))))

;; ----------------------------------------------------------------------------
;; Help
;; ----------------------------------------------------------------------------

(def common-spec
  {:help {:alias :h
          :coerce :boolean
          :desc "Show help"}})


(defn with-common-spec [spec]
  (merge common-spec spec))


(defn usage-command [usage]
  (str/replace-first usage #"^Usage:\s*" ""))


(defn command-node?
  "A leaf. A node whose :target is a FUNCTION is a command; anything else groups them,
   at whatever depth — the tree is walked, not assumed to be two deep.

   The fn? matters: a spec is free to declare an option called :target (several do),
   and a walker that only looked for the key would mistake that spec for a command."
  [x]
  (and (map? x) (fn? (:target x))))


(defn command-tree->command-rows
  ([tree] (command-tree->command-rows [] tree))
  ([path tree]
   (mapcat (fn [[k node]]
             (let [path (conj path (name k))]
               (if (command-node? node)
                 [{:path  (str/join " " path)
                   :usage (usage-command (:usage node))
                   :desc  (:desc node)}]
                 (command-tree->command-rows path node))))
           tree)))


(defn command-tree->help-message [tree]
  (let [rows (command-tree->command-rows tree)
        path-width (apply max 0 (map #(count (:path %)) rows))
        command-lines
        (->> rows
             (map
               (fn [{:keys [path usage desc]}]
                 (str
                   "  "
                   (format (str "%-" path-width "s") path)
                   "  "
                   usage
                   (when desc
                     (str "\n"
                          (apply str (repeat (+ 4 path-width) " "))
                          desc)))))
             (str/join "\n"))]
    (str
      "Usage:\n"
      "  <command> <subcommand> [options]\n"
      "\n"
      "Commands:\n"
      command-lines
      "\n"
      "\n"
      "Options:\n"
      "  -h, --help  Show help")))


(defn print-command-help! [usage spec]
  (println usage)
  (println)
  (println (cli/format-opts {:spec spec})))


(defn print-help! [tree]
  (println (command-tree->help-message tree)))

;; ----------------------------------------------------------------------------
;; Command tree helpers
;; ----------------------------------------------------------------------------

(defn help? [m]
  (true? (get-in m [:opts :help])))


(defn extra-args [m]
  (seq (:args m)))


(defn reject-extra-args! [m]
  (when-let [extra (extra-args m)]
    (user-error!
      (str "Unexpected extra argument"
           (when (> (count extra) 1) "s")
           ": "
           (str/join " " extra))
      {:extra-args extra
       :dispatch (:dispatch m)})))


(defn command-error-fn []
  (fn [{:keys [msg opts] :as data}]
    ;; Allow:
    ;;   tools pack create --help
    ;;
    ;; even if required positional args are missing.
    (when-not (:help opts)
      (throw
        (ex-info msg
                 (assoc data :type :lib.cli/user-error))))))


(defn command-entry [cmds {:keys [usage target args spec coerce allow-extra-args?]}]
  (let [spec* (with-common-spec spec)]
    (cond-> {:cmds cmds
             :args->opts args
             :spec spec*
             :restrict true
             :no-keyword-opts true
             :error-fn (command-error-fn)
             :fn (fn [m]
                   (if (help? m)
                     (print-command-help! usage spec*)
                     (do
                       (when-not allow-extra-args?
                         (reject-extra-args! m))
                       (target (assoc (:opts m) :extra-args (:args m))))))}
      coerce (assoc :coerce coerce))))

(defn command-tree->dispatch-table
  ([tree] (command-tree->dispatch-table [] tree))
  ([path tree]
   (vec (mapcat (fn [[k node]]
                  (let [cmds (conj path (name k))]
                    (if (command-node? node)
                      [(command-entry cmds node)]
                      (command-tree->dispatch-table cmds node))))
                tree))))

(defn get-command-subtree [tree dispatch]
  (reduce
    (fn [node part]
      (when (map? node)
        (get node (name part))))
    tree
    dispatch))

(defn print-command-usages! [node]
  (doseq [[_ child] node]
    (if (command-node? child)
      (println " " (:usage child))
      (print-command-usages! child))))

(defn print-input-exhausted! [tree e]
  (let [{:keys [dispatch all-commands]} (ex-data e)
        node (get-command-subtree tree dispatch)]
    (println
      (str
        "Incomplete command: "
        (str/join " " dispatch)))
    (println)

    (if (and (map? node)
             (not (command-node? node)))
      (do
        (println "Available commands:")
        (print-command-usages! node))

      (do
        (println "Available subcommands:")
        (doseq [cmd all-commands]
          (println " " (name cmd)))))))

;; ----------------------------------------------------------------------------
;; Task rendering
;;
;; A target that returns a lib.task flow can be run here and its progress rendered
;; to the terminal on a single updating line.
;; ----------------------------------------------------------------------------

(defn run-and-display!
  "Runs a cold task and renders its progress on a single updating terminal line.
   Returns the task's :done value, or re-throws its :error."
  [t]
  (task/run! t)
  (loop []
    (when-let [{:keys [type payload]} (task/take!! t)]
      (when (= :progress type)
        (print (format "\r%-16s %3d%%  %-24s"
                       (str (:name t))
                       (int (:progress payload))
                       (or (:message payload) "")))
        (flush))
      (recur)))
  (println)
  (let [tl (task/task->timeline t)]
    (if (= :error (timeline/timeline->state tl))
      (throw (:error (:payload (timeline/timeline->last-of-type tl (:id t) :error))))
      (:payload (timeline/timeline->last-of-type tl (:id t) :done)))))

;; ----------------------------------------------------------------------------
;; Public API
;; ----------------------------------------------------------------------------

(defn dispatch-table [tree]
  (conj
    (command-tree->dispatch-table tree)
    {:cmds []
     :fn (fn [_]
           (print-help! tree))}))


(defn dispatch!
  ([tree] (dispatch! tree *command-line-args*))
  ([tree args]
   (try
     (cli/dispatch (dispatch-table tree) args)

     (catch clojure.lang.ExceptionInfo e
       (binding [*out* *err*]
         (cond           
           (input-exhausted-error? e)
           (print-input-exhausted! tree e)

           (babashka-cli-error? e)
           (println
             (or (:msg (ex-data e))
                 (.getMessage e)
                 (pr-str (ex-data e))))

           :otherwise
           (do
             (println (.getMessage e))
             (when-let [data (ex-data e)]
               (when-not (= :lib.cli/user-error (:type data))
                 (println data))))))
       (System/exit 1))

     (catch Exception e
       (binding [*out* *err*]
         (println (.getMessage e)))
       (System/exit 1)))))