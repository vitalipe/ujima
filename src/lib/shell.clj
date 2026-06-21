(ns lib.shell
  "Shell DSL: build and run shell commands from Clojure forms.

   `$` starts a command (returns a process); `$!` runs it and returns its stdout,
   throwing on failure. Bare words are literal tokens; anything in `[…]`, `{…}` or `(…)` is
   evaluated as Clojure. Pipe with `->`. The comment at the bottom shows the syntax."
  (:require [babashka.process :as p]
            [clojure.string   :as str]
            [clojure.java.io  :as io]
            [lib.shell.exec   :as exec]))


;; ---------------------------------------------------------------------------
;; Value rules — lower a runtime value to argv tokens.
;; ---------------------------------------------------------------------------

(defn- map-entry->tokens
  "One map entry -> tokens: false/nil drops, true -> bare key, scalar -> `k=v`."
  [k v]
  (let [key-tok (if (keyword? k) (subs (str k) 1) (str k))]
    (cond
      (or (false? v) (nil? v)) []
      (true? v)                [key-tok]
      (string? v)              [(str key-tok "=" v)]
      (keyword? v)             [(str key-tok "=" (subs (str v) 1))]
      (symbol? v)              [(str key-tok "=" v)]
      (number? v)              [(str key-tok "=" v)]
      :else (throw (ex-info "shell: map value must be a scalar, true, false, or nil"
                            {:key k :value v})))))


(defn value->tokens
  "Lower a value to argv tokens: nil drops; string/number/keyword/symbol -> one token
   (keyword via `(subs (str v) 1)`, so :a/b -> \"a/b\"); vector/seq splices; map -> `k=v`
   pairs; set/boolean throw; anything else -> `(str v)` (Path, File, UUID, …)."
  [v]
  (cond
    (nil? v)        []
    (string? v)     [v]
    (keyword? v)    [(subs (str v) 1)]
    (symbol? v)     [(str v)]
    (number? v)     [(str v)]
    (boolean? v)    (throw (ex-info "shell: a boolean isn't a shell token outside a map"
                                    {:value v}))
    (set? v)        (throw (ex-info "shell: a set isn't a shell token; use a vector"
                                    {:value v}))
    ;; `sequential?`, not `seqable?`: a Path is seqable into its components but must stay
    ;; one token — it falls to the `(str v)` branch below.
    (sequential? v) (into [] (mapcat value->tokens) v)
    (map? v)        (into [] (mapcat #(map-entry->tokens (key %) (val %))) v)
    :else           [(str v)]))


;; ---------------------------------------------------------------------------
;; Process detection + default spawn.
;; ---------------------------------------------------------------------------

(defn process?
  "True if `x` is a process (what a spawn returns)."
  [x]
  ;; by class name: babashka's SCI can't resolve the Process class symbol for `instance?`.
  (= "babashka.process.Process" (some-> x class .getName)))


(defn spawn
  "Default spawn `(fn [opts argv]) -> process`."
  [opts argv]
  ;; varargs, not `(p/process opts argv)`: babashka mis-parses a vector cmd when opts is set.
  (apply p/process opts argv))


;; ---------------------------------------------------------------------------
;; Macro-time form lowering.
;; ---------------------------------------------------------------------------

(defn- lower-arg
  "Lower one non-head form to a token-vector expression."
  [form]
  (cond
    (nil? form)     []
    (symbol? form)  [(str form)]
    (keyword? form) [(subs (str form) 1)]
    (string? form)  [form]
    (number? form)  [(str form)]
    (set? form)     (throw (ex-info "shell: a set isn't a shell token; use a vector"
                                    {:form form}))
    (boolean? form) (throw (ex-info "shell: a boolean isn't a shell token outside a map"
                                    {:form form}))
    :else           `(value->tokens ~form)))


(defn- lower-head
  "Lower the head form to its value expression. Bare symbol/keyword become literal strings;
   everything else evaluates at runtime (so a `->`-threaded process flows through)."
  [form]
  (cond
    (symbol? form)  (str form)
    (keyword? form) (subs (str form) 1)
    (set? form)     (throw (ex-info "shell: a set isn't a shell token; use a vector"
                                    {:form form}))
    (boolean? form) (throw (ex-info "shell: a boolean isn't a shell token outside a map"
                                    {:form form}))
    :else           form))


;; ---------------------------------------------------------------------------
;; Macros.
;; ---------------------------------------------------------------------------

(defmacro $*
  "Like `$`, but with an explicit spawn `(fn [opts argv]) -> process` as the first
   argument — the seam a project uses to add command remap or sudo."
  [spawn & forms]
  (when (empty? forms)
    (throw (ex-info "shell: $* requires a command" {:forms forms})))
  `(let [head#  ~(lower-head (first forms))
         args#  (into [] cat [~@(map lower-arg (rest forms))])
         prev?# (process? head#)
         argv#  (if prev?# args# (into (value->tokens head#) args#))]
     (when (or (empty? argv#) (str/blank? (str (first argv#))))
       (throw (ex-info "shell: empty command / blank argv[0]" {:argv argv#})))
     (~spawn (if prev?# {:prev head#} {}) argv#)))


(defmacro $
  "Build a command from shell-DSL forms and start it (returns a process). Pipe with `->`:
   `(-> ($ echo hi) ($ cat))`."
  [& forms]
  `($* spawn ~@forms))


(defmacro $!
  "Run a command and return its trimmed stdout, throwing on a non-zero exit."
  [& forms]
  `(exec/out-or-fail! ($ ~@forms)))


(defmacro $argv
  "Lower forms to `{:cmd argv :opts {}}` without running — a dry run / explain."
  [& forms]
  (when (empty? forms)
    (throw (ex-info "shell: $argv requires a command" {:forms forms})))
  `{:cmd  (into (value->tokens ~(lower-head (first forms)))
                (into [] cat [~@(map lower-arg (rest forms))]))
    :opts {}})


(defmacro $>*
  "Redirect a previous process's stdout into a file via a `cat` stage through `spawn`."
  [spawn prev target]
  `(~spawn {:prev ~prev :out (io/file ~target)} ["cat"]))


(defmacro $>
  "Redirect the previous process's stdout into `target` (a file). Use inside a `->` pipe."
  [prev target]
  `($>* spawn ~prev ~target))


(comment

  ($ git --no-pager log)       ;; => git --no-pager log
  ($ tail -n 100 [file])       ;; => tail -n 100 <file>      
  ($ dd {:if src :of dst})      ;; => dd if=<src> of=<dst>    
  ($ rm -rf (fs/path dir))      ;; => rm -rf <dir>            


  ($! ls (when list? :-l) "/home") ;; => if list?
                                   ;;      ls -l /home
                                   ;;      ls /home


  (require '[lib.shell :refer [$ $! $argv]]
           '[lib.shell.exec :refer [out-or-fail!]])

  ($argv git --oneline log)   ; => {:cmd ["git" "--oneline" "log"] :opts {}}
  ($! echo "hello")           ; => "hello"  (throws if the command fails)


  (-> ($ echo "ujima rocks") 
      ($ grep ujima) 
      (out-or-fail!))) ; => "ujima rocks"
