(ns ujima.linux.shell
  (:require [clojure.string  :as str]
            [babashka.process :refer [shell] :as p]
            [ujima.env        :refer [get-in-env]]))


(defn- only-one-expr! [form]
  (when (empty? form)
    (throw
      (ex-info "Shell macro [] cannot be empty"
               {:form form})))

  (when (> (count form) 1)
    (throw
      (ex-info "Shell macro [] must contain exactly one expression"
               {:form form})))

  (first form))


(defn- form->shell-token [form]
  (cond
    ;; Shell-like literal token:
    ;;   ($ curl --fail --location ...)
    ;; => "curl" "--fail" "--location"
    (symbol? form)  (name form)
    (keyword? form) (name form)

    ;; Explicit Clojure evaluation:
    ;;   ($ cat [path])
    ;; => (str path)
    (vector? form) `(str ~(only-one-expr! form))

    ;; Avoid accidental evaluation.
    ;; Use [(...)] when evaluation is intended.
    (seq? form) (throw
                  (ex-info "Use [...] for evaluated shell macro expressions"
                           {:form form}))

    ;; Strings, numbers, paths, etc.
    :otherwise (str form)))


(defn re-map->cmd [cmd]
  (let [remap (get-in-env [:shell :commands] {})]
    (cond
      (keyword? cmd)          (get remap cmd (name cmd))
      (symbol? cmd)           (get remap (keyword (name cmd)) (name cmd))
      (not (string? cmd))     (str cmd)
      (str/includes? cmd "/") cmd ;; don't remap concrete paths
      :otherwise              (get remap (keyword cmd) cmd))))


(defn- remap-proc [cmd & args]
  (apply p/process (re-map->cmd cmd) args))


(defn- remap-proc-with-prv [prv cmd & args]
  (apply p/process prv (re-map->cmd cmd) args))


(defn- process-call-form [forms prefix-forms]
  (let [[maybe-prv & rest-forms] forms
        [prv forms]              (if (seq? maybe-prv) ;; threaded?
                                    [maybe-prv rest-forms]
                                    [nil forms])]
    (when-not (seq forms)
      (throw
        (ex-info "Shell macro requires a command" {:form forms})))

    (let [tokens (->> forms
                   (mapv form->shell-token)
                   (concat prefix-forms))]
      (if prv
        `(remap-proc-with-prv {:prev ~prv} ~@tokens)
        `(remap-proc ~@tokens)))))


(defn sh
  "Runs a command. Returns a result map. Does not throw."
  [cmd & args]
  (let [result (apply shell {:out :string :err :string :continue true}
                            (re-map->cmd cmd)
                            args)]

    {:ok?   (zero? (:exit result))
     :exit  (:exit result)
     :out   (str/trim (:out result))
     :err   (str/trim (:err result))}))


(defn sudo
  [cmd & args]
  (apply sh :sudo "-n" (re-map->cmd cmd) args))


(defn sh!
  "Runs a command. returns stdout, Throws on non-zero exit."
  [cmd & args]
  (let [{:keys [ok? out] :as result} (apply sh cmd args)]
    (when-not ok?
      (throw
        (ex-info (str "Command failed: " (re-map->cmd cmd) " " (str/join " " args)) result)))

    out))


(defn sudo!
  "Runs sudo command. Throws on non-zero exit."
  [cmd & args]
  (apply sh! :sudo "-n" (re-map->cmd cmd) args))


(defmacro $
  "Starts a process.

   Symbols become literal shell tokens.
   [expr] evaluates expr and stringifies it.

   Examples:
     ($ echo hello)
     ($ cat [path])

   Supports thread-first piping:
     (-> ($ echo hello)
         ($ grep hell)
         (out-or-fail!))"
  [& forms]
  (process-call-form forms []))


(defmacro sudo$
  "Starts a process through sudo.

   Examples:
     (sudo$ mount -t ext4 [device] [mnt])

   Supports thread-first piping:
     (-> (sudo$ cat /root/file)
         ($ grep ujima)
         (out-or-fail!))"
  [& forms]
  (process-call-form forms ["sudo" "-n"]))


(defn pipeline-or-fail!
  "Checks every process in a process pipeline.

   Returns a vector of checked process results."
  [proc]
  (mapv p/check (p/pipeline proc)))


(defn result-or-fail!
  "Checks every process in a process pipeline.

   Returns the final checked process result."
  [proc]
  (last (pipeline-or-fail! proc)))


(defn out-or-fail!
  "Reads stdout from the final process, then checks every process in the pipeline.

   Returns stdout as a string."
  [proc]
  (let [out (slurp (:out proc))]
    (pipeline-or-fail! proc)
    (str/trim out)))


(defmacro $!
  "Shell syntax sugar over sh!. Not pipeable. Returns trimmed stdout."
  [& forms]
  `(sh! ~@(mapv form->shell-token forms)))


(defmacro sudo$!
  "Shell syntax sugar over sudo!. Not pipeable. Returns trimmed stdout."
  [& forms]
  `(sudo! ~@(mapv form->shell-token forms)))


(defmacro $>
  "Redirects stdout from the previous process into target.

   Intended for use inside thread-first process pipelines:

     (-> ($ curl --fail --location [url])
         ($ xz -dc)
         ($> (fs/file image-path))
         (result-or-fail!))

   This starts a final `cat` process with:
     :prev previous-process
     :out  target"
  [prev target]
  `(p/process {:prev ~prev :out  (clojure.java.io/file ~target)} (re-map->cmd :cat)))


(defn root? []
  (or (= "0" (str/trim (:out (sh :id "-u"))))
      (:ok? (sh :sudo "-n" "true"))))


(defn require-root! []
  (when-not (root?)
    (throw
      (ex-info "This operation requires root"
               {:type :ujima/root-required}))))
