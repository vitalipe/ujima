(ns ujima.linux.shell
  "Project shell layer over `lib.shell`: the same DSL (`$`, `$!`, `$?`, `$>`, `->` piping)
   with env command-remap applied to every command (argv[0] only, concrete paths skipped),
   plus the sudo family (`sudo$`/`sudo$!`/`sudo$?`) which remaps the wrapped command and
   prepends a (remapped) `sudo -n`. `$?`/`sudo$?` run eagerly and return
   `{:ok? :exit :out :err}` without throwing; `root?`/`require-root!` are the only functions."
  (:require [clojure.string :as str]
            [ujima.env      :refer [get-in-env]]
            [lib.shell      :as shell]
            [lib.shell.exec :as exec]))


;; ---------------------------------------------------------------------------
;; Command remap — argv[0] only, from [:shell :commands].
;; ---------------------------------------------------------------------------

(defn remap-cmd
  "Resolve a command token to a token vector via the `[:shell :commands]` table. The
   looked-up value is a v3 fragment lowered by `lib.shell/value->tokens` (a scalar is one
   token, a vector splices). Concrete paths (containing '/') and unmapped tokens pass
   through unchanged."
  [tok]
  (let [remap     (get-in-env [:shell :commands] {})
        concrete? (and (string? tok) (str/includes? tok "/"))
        k         (cond
                    (keyword? tok) tok
                    (symbol? tok)  (keyword (name tok))
                    (string? tok)  (keyword tok))]
    (if (and k (not concrete?) (contains? remap k))
      (shell/value->tokens (get remap k))
      (shell/value->tokens tok))))


(defn remap-argv
  "Remap argv[0] only; arguments are left untouched."
  [argv]
  (into (remap-cmd (first argv)) (rest argv)))


;; ---------------------------------------------------------------------------
;; Spawns — plain (remap) and sudo (remap THEN prepend a remapped `sudo -n`).
;; ---------------------------------------------------------------------------

(defn remap-spawn [opts argv]
  (shell/spawn opts (remap-argv argv)))


(defn sudo-spawn
  "Remap the user argv, then prepend a (remapped) `sudo -n`. Both the wrapped command and
   sudo are remapped."
  [opts argv]
  (shell/spawn opts (-> (remap-cmd "sudo")
                        (conj "-n")
                        (into (remap-argv argv)))))


;; ---------------------------------------------------------------------------
;; Macros — the remap/sudo DSL over lib.shell.
;; ---------------------------------------------------------------------------

(defmacro $
  "Like `lib.shell/$`, with env command-remap applied. Supports `->` piping."
  [& forms]
  `(shell/$* remap-spawn ~@forms))


(defmacro sudo$
  "Like `$`, but through a (remapped) `sudo -n`; the wrapped command is remapped too."
  [& forms]
  `(shell/$* sudo-spawn ~@forms))


(defmacro $!
  "Run a command (remap) and return its trimmed stdout, throwing on a non-zero exit."
  [& forms]
  `(exec/out-or-fail! ($ ~@forms)))


(defmacro sudo$!
  "Run a command through sudo (remap) and return its trimmed stdout, throwing on non-zero."
  [& forms]
  `(exec/out-or-fail! (sudo$ ~@forms)))


(def capture-opts
  "Process opts for string capture, used by `$?` / `sudo$?`."
  {:out :string :err :string :continue true})


(defmacro $?
  "Run a command (remap) eagerly; return `{:ok? :exit :out :err}`. Does not throw."
  [& forms]
  `(exec/result! (remap-spawn capture-opts (:cmd (shell/$argv ~@forms)))))


(defmacro sudo$?
  "Run a command through sudo (remap) eagerly; return `{:ok? :exit :out :err}`. No throw."
  [& forms]
  `(exec/result! (sudo-spawn capture-opts (:cmd (shell/$argv ~@forms)))))


(defmacro $>
  "Redirect the previous process's stdout into `target` (a file); the `cat` stage is
   remapped. Use inside a `->` pipe."
  [prev target]
  `(shell/$>* remap-spawn ~prev ~target))


;; Finishers re-exported so call-sites can keep referring them from this ns.
(def pipeline-or-fail! exec/pipeline-or-fail!)
(def result-or-fail!   exec/result-or-fail!)
(def out-or-fail!      exec/out-or-fail!)


;; ---------------------------------------------------------------------------
;; Root helpers.
;; ---------------------------------------------------------------------------

(defn root? []
  (or (= "0" (str/trim (:out ($? id -u))))
      (:ok? ($? sudo -n "true"))))


(defn require-root! []
  (when-not (root?)
    (throw (ex-info "This operation requires root"
                    {:type :ujima/root-required}))))
