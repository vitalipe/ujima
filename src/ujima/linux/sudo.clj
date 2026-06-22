(ns ujima.linux.sudo
  "Sudo / root layer over `lib.shell`. `with-sudo` composes a `sudo -n` prefix onto the current
   `*spawn*` for its dynamic extent; the baseline remap is sudo-aware, so it still rewrites the
   *wrapped* command (not `sudo`). `sudo$`/`sudo$!`/`sudo$?` and `sudo`/`sudo!`/`sudo?` are
   sugar over `with-sudo`. `install-remap!` installs the project's command-remap as the
   `*spawn*` baseline (call once from the entry point). `root?`/`require-root!` round it out.

   This is the only place sudo lives: the in-distro agent elevates per command via these;
   outside tools run the whole process under `sudo bb` and just use `lib.shell` directly."
  (:require [clojure.string    :as str]
            [lib.shell         :as shell]
            [lib.shell.command :as cmd]))


(defn sudo-wrap
  "Spawn decorator: prepend `sudo -n` to argv. The sudo-aware baseline remap skips this prefix
   and rewrites the wrapped command, so remap + sudo compose correctly."
  [spawn]
  (fn [opts argv] (spawn opts (into ["sudo" "-n"] argv))))


(defmacro with-sudo
  "Run `body` with `*spawn*` wrapped to prepend `sudo -n`."
  [& body]
  `(binding [shell/*spawn* (sudo-wrap shell/*spawn*)] ~@body))


(defmacro sudo$  [& forms] `(with-sudo (shell/$  ~@forms)))
(defmacro sudo$! [& forms] `(with-sudo (shell/$! ~@forms)))
(defmacro sudo$? [& forms] `(with-sudo (shell/$? ~@forms)))


(defn sudo  [& args] (with-sudo (apply shell/sh  args)))
(defn sudo! [& args] (with-sudo (apply shell/sh! args)))
(defn sudo? [& args] (with-sudo (apply shell/sh? args)))


(defn install-remap!
  "Install `table` (a `[:shell :commands]` remap map) as the baseline `*spawn*` via
   `alter-var-root` — a root binding, so it's global and survives across threads. Call once
   from the entry point after config is read."
  [table]
  (alter-var-root #'lib.shell/*spawn* (constantly ((shell/remapping table) cmd/spawn))))


(defn root? []
  (or (= "0" (str/trim (:out (shell/$? id -u))))
      (:ok? (shell/$? sudo -n "true"))))


(defn require-root! []
  (when-not (root?)
    (throw (ex-info "This operation requires root"
                    {:type :ujima/root-required}))))
