(ns lib.shell.command
  "Shell DSL engine: build and run shell commands from Clojure forms/data through an EXPLICIT
   spawn. Bare words are literal tokens; anything in `[…]`, `{…}` or `(…)` is evaluated as
   Clojure. Pipe with `->`.

   Standalone (babashka.process + clojure core) and context-free — the spawn is always passed
   in (`$*`/`sh*`). The dynamic-`*spawn*` convenience API (`$`/`$!`/`$?`, remap, sudo) lives in
   `lib.shell`; `$*`/`sh*` here are that layer's substrate and its explicit escape hatch."
  (:require [babashka.process :as p]
            [clojure.string   :as str]
            [clojure.java.io  :as io]))


;; ---------------------------------------------------------------------------
;; Value rules — lower a runtime value to argv tokens.
;; ---------------------------------------------------------------------------

(defn- map-entry->tokens
  "One map entry -> tokens: false/nil drops, true -> bare key, a collection value is an
   error; anything else glues as `k=v` (keyword via subs, else via str — so Path/File/UUID
   work, mirroring `value->tokens`)."
  [k v]
  (let [key-tok (if (keyword? k) (subs (str k) 1) (str k))]
    (cond
      (or (false? v) (nil? v))               []
      (true? v)                              [key-tok]
      (keyword? v)                           [(str key-tok "=" (subs (str v) 1))]
      (or (sequential? v) (set? v) (map? v)) (throw (ex-info "shell: a map value can't be a collection (it must glue to one token)"
                                                             {:key k :value v}))
      :else                                  [(str key-tok "=" v)])))


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


(defn ->argv
  "Lower already-evaluated values to argv tokens (each via `value->tokens`, splicing).
   The runtime core shared by `sh*` and `$argv`."
  [& vals]
  (into [] (mapcat value->tokens) vals))


;; ---------------------------------------------------------------------------
;; Process detection + default (terminal) spawn.
;; ---------------------------------------------------------------------------

(defn process?
  "True if `x` is a process (what a spawn returns)."
  [x]
  ;; by class name: babashka's SCI can't resolve the Process class symbol for `instance?`.
  (= "babashka.process.Process" (some-> x class .getName)))


(defn spawn
  "Terminal spawn `(fn [opts argv]) -> process`. The default root value of `lib.shell/*spawn*`."
  [opts argv]
  ;; varargs, not `(p/process opts argv)`: babashka mis-parses a vector cmd when opts is set.
  (apply p/process opts argv))


(defn sh*
  "Run a command from already-evaluated Clojure data through `spawn`. `cmd` is argv[0] (or a
   previous process, which becomes a `:prev` pipe stage); `args` splice via `value->tokens`.
   Returns the spawn's process. Opts ride on `spawn`."
  [spawn cmd & args]
  (let [prev? (process? cmd)
        argv  (if prev? (apply ->argv args) (apply ->argv cmd args))]
    (when (or (empty? argv) (str/blank? (str (first argv))))
      (throw (ex-info "shell: empty command / blank argv[0]" {:argv argv})))
    (spawn (if prev? {:prev cmd} {}) argv)))


;; ---------------------------------------------------------------------------
;; Macro-time form lowering.
;; ---------------------------------------------------------------------------

(defn- lower-form
  "Lower one DSL form to a value expression for `sh*`/`->argv`. A bare symbol is a literal
   token (its name); set/boolean literals throw at macroexpand; everything else (keyword,
   string, number, nil, `[..]`/`{..}`/`(..)`, a threaded process) passes through and is
   `value->tokens`'d at runtime."
  [form]
  (cond
    (symbol? form)  (str form)
    (set? form)     (throw (ex-info "shell: a set isn't a shell token; use a vector"
                                    {:form form}))
    (boolean? form) (throw (ex-info "shell: a boolean isn't a shell token outside a map"
                                    {:form form}))
    :else           form))


;; ---------------------------------------------------------------------------
;; Macros — explicit-spawn primitives.
;; ---------------------------------------------------------------------------

(defmacro $*
  "Build a command from shell-DSL forms and run it through the explicit `spawn`
   `(fn [opts argv]) -> process` — the seam a project layers remap/sudo/logging onto."
  [spawn & forms]
  (when (empty? forms)
    (throw (ex-info "shell: $* requires a command" {:forms forms})))
  ;; `mapv`, not `map`: force the lowering eagerly so set/boolean forms throw at macroexpand
  ;; (a lazy `~@(map …)` defers the error to compile time and slips past `macroexpand-1`).
  `(sh* ~spawn ~@(mapv lower-form forms)))


(defmacro $argv
  "Lower forms to `{:cmd argv :opts {}}` without running — a dry run / explain."
  [& forms]
  (when (empty? forms)
    (throw (ex-info "shell: $argv requires a command" {:forms forms})))
  `{:cmd (->argv ~@(mapv lower-form forms)) :opts {}})


(defmacro $>
  "Redirect the previous process's stdout into `target` (a file) via a trivial `cat` stage,
   through the terminal `spawn` (never remapped — `cat` is internal plumbing). Use in a `->`
   pipe."
  [prev target]
  `(spawn {:prev ~prev :out (io/file ~target)} ["cat"]))


(comment
  ;; explicit-spawn engine (see lib.shell for the *spawn*-backed `$`/`$!`/`$?`):

  ($*    spawn git --no-pager log)       ; => git --no-pager log
  ($argv git --oneline log)              ; => {:cmd ["git" "--oneline" "log"] :opts {}}
  ($*    spawn dd {:if src :of dst})     ; => dd if=<src> of=<dst>
  (sh*   spawn :rm :-rf (fs/path dir)))  ; => rm -rf <dir>   (data args)
