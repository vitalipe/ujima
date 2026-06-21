(ns lib.shell.exec
  "Finishers for the Process / pipeline values produced by `lib.shell`.

   There is exactly one execution engine — babashka.process, async and pipeable — and
   every other behaviour (block, capture, throw-on-failure) is a finisher applied to a
   Process here, rather than a second blocking engine or a combinatorial explosion of
   `$`-macro variants. Choose one at the call site:

     out-or-fail!       slurp stdout, check the whole pipeline, throw on non-zero -> string
     result-or-fail!    check the whole pipeline, return the final checked process map
     pipeline-or-fail!  check every stage, return all checked process maps
     result!            wait, return {:ok? :exit :out :err}, never throws

   Stateless and dependency-light (babashka.process only), so it is shareable alongside
   `lib.shell`."
  (:require [babashka.process :as p]
            [clojure.string   :as str]))


(defn pipeline-or-fail!
  "Checks every process in a pipeline. Returns a vector of checked process results."
  [proc]
  (mapv p/check (p/pipeline proc)))


(defn result-or-fail!
  "Checks every process in a pipeline. Returns the final checked process result."
  [proc]
  (last (pipeline-or-fail! proc)))


(defn out-or-fail!
  "Reads stdout from the final process, then checks every process in the pipeline.
   Returns trimmed stdout as a string."
  [proc]
  (let [out (slurp (:out proc))]
    (pipeline-or-fail! proc)
    (str/trim out)))


(defn result!
  "Waits for `proc` and returns `{:ok? :exit :out :err}` without throwing.

   Expects the process to have been started with string capture (`:out :string
   :err :string`) — the shape the function-style `sh`/`sudo` API returns."
  [proc]
  (let [{:keys [exit out err]} @proc]
    {:ok?  (zero? exit)
     :exit exit
     :out  (str/trim (str out))
     :err  (str/trim (str err))}))
