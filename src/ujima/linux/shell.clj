(ns ujima.linux.shell
  "Project shell layer over `lib.shell`: env-driven command remap + sudo, plus the
   function-style `sh`/`sudo` API.

   Same DSL as `lib.shell` (`$`, `$!`, `$>`, `->` piping). On top it adds:
   - command remap from `[:shell :commands]` — argv[0] only, concrete paths skipped;
   - `sudo$`/`sudo$!`, which remap the wrapped command and then prepend a (remapped)
     `sudo -n` (so both get remapped)."
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
;; Function-style API — preserved signatures, heavily used by call-sites.
;; ---------------------------------------------------------------------------

(def ^:private capture-opts {:out :string :err :string :continue true})

(defn- ->tokens [cmd args]
  (shell/value->tokens (cons cmd args)))


(defn sh
  "Run a command. Returns a result map `{:ok? :exit :out :err}`. Does not throw."
  [cmd & args]
  (exec/result! (remap-spawn capture-opts (->tokens cmd args))))


(defn sudo
  "Run a command through sudo. Returns a result map. Does not throw."
  [cmd & args]
  (exec/result! (sudo-spawn capture-opts (->tokens cmd args))))


(defn root? []
  (or (= "0" (str/trim (:out (sh :id "-u"))))
      (:ok? (sh :sudo "-n" "true"))))


(defn require-root! []
  (when-not (root?)
    (throw (ex-info "This operation requires root"
                    {:type :ujima/root-required}))))
