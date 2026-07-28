(ns ujima.linux.sudo
  "Sudo layer over `lib.shell`: `with-sudo` composes a `sudo -n` prefix onto the current
   `*spawn*` for its dynamic extent (the sudo-aware baseline remap still rewrites the WRAPPED
   command, not `sudo`). `sudo$`/`sudo$!`/`sudo$?` and `sudo`/`sudo!`/`sudo?` are sugar over it.

   This is in-distro ujimad's per-command elevation. Build tools run the whole process under
   `sudo bb` and use `lib.shell` directly (+ `lib.shell/require-root!`), so they don't need this."
  (:require [lib.shell :as shell]))


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
