(ns lib.shell
  "Shell DSL convenience layer over `lib.shell.command`: the same DSL, but commands run
   through a dynamic spawn `*spawn*`, so command-remap / sudo / logging layer on transparently.

   `$`/`$!`/`$?` (+ `sh`/`sh!`/`sh?` fn forms for data args) read `*spawn*`. Set the baseline
   once with `alter-var-root` (e.g. a project installs its remap at startup); compose per-op
   with `with-spawn`/`with-remap`. `lib.shell.command/$*`/`sh*` are the explicit-spawn escape
   hatch when you don't want the ambient `*spawn*` (concurrent code, lazy seqs).

   Standalone: this lib has no project deps — a remap table is supplied by the caller, never
   read from any global env."
  (:require [clojure.string    :as str]
            [lib.shell.command :as cmd]
            [lib.shell.exec    :as exec]))


;; ---------------------------------------------------------------------------
;; The dynamic spawn — what the convenience API runs through.
;; ---------------------------------------------------------------------------

(def ^:dynamic *spawn*
  "The spawn `$`/`$!`/`$?` run through. Defaults to the plain terminal spawn (no remap, no
   sudo). Install a remap baseline with `alter-var-root` (root binding -> global, thread-safe);
   compose per-op with `with-spawn`/`with-remap` (a nested `binding`)."
  cmd/spawn)


;; ---------------------------------------------------------------------------
;; Capture — an OPTS decorator (composes freely; doesn't touch argv). For the `?` runners.
;; ---------------------------------------------------------------------------

(def capture-opts
  "Process opts for string capture, used by the `?` runners (`$?`/`sh?`)."
  {:out :string :err :string :continue true})


(defn capturing
  "Wrap `spawn` so the process captures stdout/stderr as strings and never throws — the shape
   `result!` needs."
  [spawn]
  (fn [opts argv] (spawn (merge capture-opts opts) argv)))


;; ---------------------------------------------------------------------------
;; Console output — a spawn decorator that streams a command's output as it runs (build
;; scripts). Composes freely; capturing (`$?`) calls stay silent.
;; ---------------------------------------------------------------------------

(defn- tee-stream
  "An InputStream over `src` that also copies everything to `echo` as it flows. A pump thread
   drains `src` -> `echo` (live console) + a pipe; the pipe is the returned stream, so a normal
   reader (`out-or-fail!`'s slurp) gets the bytes unchanged and still prints. (A pump, not an
   echo-on-read wrapper, because babashka's SCI can't `proxy` a stream class.)"
  [^java.io.InputStream src ^java.io.OutputStream echo]
  (let [sink (java.io.PipedOutputStream.)
        out  (java.io.PipedInputStream. sink (* 64 1024))]
    (future
      (try
        (let [buf ^bytes (byte-array 8192)]
          (loop []
            (let [n (.read src buf)]
              (when (pos? n)
                (.write echo buf 0 n) (.flush echo)
                (.write sink buf 0 n)
                (recur)))))
        (catch Exception _ nil)
        (finally (.close sink))))
    out))


(defn console-out
  "Spawn decorator: stream each command's stdout + stderr to the console as it runs. Capturing
   calls (`:out :string`, i.e. `$?`) pass through untouched, so query runners stay silent.
   `out-or-fail!` / `$!` are unchanged — they slurp the wrapped `:out`, which prints as a side
   effect of being read yet still yields the captured string."
  [spawn]
  (fn [opts argv]
    (if (= :string (:out opts))
      (spawn opts argv)
      (let [proc (spawn (assoc opts :err :inherit) argv)]
        (assoc proc :out (tee-stream (:out proc) System/out))))))


;; ---------------------------------------------------------------------------
;; Command remap — sudo-aware, table-driven, an ARGV transform (so it composes in order).
;; ---------------------------------------------------------------------------

(defn- remap-token
  "Rewrite one command token (string) via `table` (keyword -> v3 fragment). Concrete paths
   (containing '/') and unmapped tokens pass through. Returns a token vector (the fragment is
   lowered by `value->tokens`, so a scalar is one token and a vector splices)."
  [table tok]
  (if (and (not (str/includes? tok "/")) (contains? table (keyword tok)))
    (cmd/value->tokens (get table (keyword tok)))
    [tok]))


(defn remap-argv
  "Remap the command token in `argv` via `table`. argv[0]-only, EXCEPT a leading `sudo` plus
   its `-flags` are skipped, so the *real* command (not `sudo`) is the one rewritten — that's
   what lets a `sudo -n` prefix compose with remap. Concrete '/'-paths pass through. The
   sudo-skip is deliberately narrow: it handles `sudo -n <cmd>`, not `sudo -u user <cmd>`."
  [table argv]
  (let [argv    (vec argv)
        cmd-idx (if (= "sudo" (first argv))
                  (loop [i 1]
                    (if (and (< i (count argv)) (str/starts-with? (str (nth argv i)) "-"))
                      (recur (inc i))
                      i))
                  0)]
    (if (< cmd-idx (count argv))
      (-> (subvec argv 0 cmd-idx)
          (into (remap-token table (str (nth argv cmd-idx))))
          (into (subvec argv (inc cmd-idx))))
      argv)))


(defn remapping
  "Spawn decorator: wrap `spawn` so argv is remapped via `table` before spawning. Build a
   baseline with e.g. `((remapping table) lib.shell.command/spawn)`."
  [table]
  (fn [spawn] (fn [opts argv] (spawn opts (remap-argv table argv)))))


;; ---------------------------------------------------------------------------
;; Context macros — rebind *spawn* for a dynamic extent.
;; ---------------------------------------------------------------------------

(defmacro with-spawn
  "Run `body` with `*spawn*` bound to `spawn`."
  [spawn & body]
  `(binding [*spawn* ~spawn] ~@body))


(defmacro with-remap
  "Run `body` with command-remap (via `table`) composed onto the current `*spawn*`."
  [table & body]
  `(binding [*spawn* ((remapping ~table) *spawn*)] ~@body))


(defmacro with-console-out
  "Run `body` with every `$`/`$!` command streaming its output to the console as it runs
   (composes `console-out` onto the current `*spawn*`). `$?` stays silent."
  [& body]
  `(with-spawn (console-out *spawn*) ~@body))


;; ---------------------------------------------------------------------------
;; The convenience API — runs through *spawn*.
;; ---------------------------------------------------------------------------

(defmacro $
  "Build a command from shell-DSL forms and start it through `*spawn*` (returns a process).
   Pipe with `->`: `(-> ($ echo hi) ($ cat))`."
  [& forms]
  `(cmd/$* *spawn* ~@forms))


(defmacro $!
  "Run a command through `*spawn*`; return trimmed stdout, throwing on a non-zero exit."
  [& forms]
  `(exec/out-or-fail! (cmd/$* *spawn* ~@forms)))


(defmacro $?
  "Run a command through `*spawn*` eagerly; return `{:ok? :exit :out :err}`. Does not throw."
  [& forms]
  `(exec/result! (cmd/$* (capturing *spawn*) ~@forms)))


(defmacro $argv
  "Lower forms to `{:cmd argv :opts {}}` without running — a dry run / explain."
  [& forms]
  `(cmd/$argv ~@forms))


(defmacro $>
  "Redirect the previous process's stdout into `target` (a file). Use inside a `->` pipe.
   Runs through the terminal spawn, never remapped (`cat` is internal plumbing)."
  [prev target]
  `(cmd/$> ~prev ~target))


(defn sh
  "Run a command (from data) through `*spawn*`; return the process. Fn form of `$`."
  [& args]
  (apply cmd/sh* *spawn* args))


(defn sh!
  "Run a command (from data) through `*spawn*`; return trimmed stdout, throwing. Fn form of `$!`."
  [& args]
  (exec/out-or-fail! (apply cmd/sh* *spawn* args)))


(defn sh?
  "Run a command (from data) through `*spawn*` eagerly; return a result map, no throw. Fn form of `$?`."
  [& args]
  (exec/result! (apply cmd/sh* (capturing *spawn*) args)))


;; Finishers re-exported so the convenience layer is one-stop.
(def out-or-fail!      exec/out-or-fail!)
(def result-or-fail!   exec/result-or-fail!)
(def pipeline-or-fail! exec/pipeline-or-fail!)
(def result!           exec/result!)


;; ---------------------------------------------------------------------------
;; Baseline install + root checks.
;; ---------------------------------------------------------------------------

(defn install-remap!
  "Install `table` (a command-remap map) as the baseline `*spawn*` via `alter-var-root` — a
   root binding, global and thread-safe. Call once from an entry point after reading config."
  [table]
  (alter-var-root #'*spawn* (constantly ((remapping table) cmd/spawn))))


(defn root?
  "True iff the current process is literally root (uid 0). Having passwordless sudo is
   NOT root: tools that shell out with bare `$!` need a real root process (`sudo bb`)."
  []
  (= "0" (str/trim (:out ($? id -u)))))


(defn require-root!
  "Throw unless the process is root (uid 0). Build tools run as `sudo bb`; a plain `bb`
   invocation fails here, before any heavy work (download / loopback / chroot)."
  []
  (when-not (root?)
    (throw (ex-info "This operation requires root — run it as `sudo bb …`"
                    {:type :lib.shell/root-required}))))


(comment
  ;; -------------------------------------------------------------------------
  ;; lib.shell — Clojure forms in, processes out.
  ;;   a bare word is a literal token; [..] {..} (..) evaluate as Clojure.
  ;;   every command runs through the dynamic *spawn* (remap/sudo/logging
  ;;   layer onto it); `$argv` shows the lowering without running anything.
  ;; -------------------------------------------------------------------------

  ;; three runners — same forms, you pick the finisher:
  ($  git status)               ;; => a process — pipe it, or hand it to a finisher
  ($! git rev-parse HEAD)       ;; => "9e12586…"  trimmed stdout, THROWS on non-zero
  ($? systemctl is-active ufw)  ;; => {:ok? true :exit 0 :out "active" :err ""}  (never throws)

  ;; a value becomes argv tokens — inspect it with $argv (the dry run):
  (let [file "/etc/hosts"  n 20  follow? true]
    ($argv tail (when follow? :-f) :-n [n] [file]))
  ;;   tail        bare word  -> literal "tail"
  ;;   (when …)    false/nil DROPS; here it yields :-f
  ;;   :-f :-n     a keyword is just a token  -> "-f" "-n"
  ;;   [n] [file]  [..] uses the VALUE        -> "20" "/etc/hosts"
  ;; => {:cmd ["tail" "-f" "-n" "20" "/etc/hosts"] :opts {}}

  ($argv dd {:if "/dev/sda" :of "/dev/sdb" :bs "4M"})    ;; {map} -> k=v tokens
  ;; => {:cmd ["dd" "if=/dev/sda" "of=/dev/sdb" "bs=4M"] :opts {}}

  ($argv cp -r ["a" "b"] (fs/path "/tmp" id))            ;; vectors splice; a Path stays ONE token
  ;; => {:cmd ["cp" "-r" "a" "b" "/tmp/<id>"] :opts {}}

  ;; pipe with -> ; send the final stdout to a file with $> :
  (-> ($ cat "/var/log/syslog") 
      ($ grep -i error) 
      ($ tail -n 3) 
      (out-or-fail!))
  
  (-> ($ tar --zstd -cf - "src") 
      ($> "/tmp/src.tzst"))

  ;; fn forms (sh / sh! / sh?): same value rules, DATA args — so you can build a
  ;; command programmatically (apply / map / reduce):
  (sh! :git "rev-parse" "HEAD")              ;; => "9e12586…"
  (apply sh! :git "log" "--oneline" flags)

  ;; *spawn* is the seam. Install a remap baseline once (dev echo-stubs, path pins):
  (install-remap! {:dd ["echo" "dd"]  :mkfs.ext4 "/opt/sbin/mkfs.ext4"})
  (with-remap {:ls "/usr/local/bin/ls"} ($! ls -la))     ;; … or compose it per-op

  ;; and since *spawn* is just a (fn [opts argv]), you can DECORATE it —
  ;; e.g. log every command right before it runs:
  (let [run *spawn*]
    (with-spawn (fn [opts argv] (println "$" (str/join " " argv)) (run opts argv))
      ($! uname -a)))                        ;; prints `$ uname -a`, then runs it

  ;; explicit escape hatch — pass the spawn, skip the ambient *spawn*
  ;; (raw threads / lazy seqs that escape a binding, or precise control):
  (out-or-fail! (lib.shell.command/$* cmd/spawn git status)))
