(ns lib.shell.command-test
  "Engine tests: value lowering, argv building, the explicit-spawn primitives ($* / sh* /
   $argv / $>). The dynamic-*spawn* convenience layer (remap, $/$!/$?) lives in lib.shell-test."
  (:require [clojure.test      :refer [deftest is testing]]
            [clojure.java.io   :as io]
            [babashka.fs       :as fs]
            [babashka.process  :as p]
            [lib.shell.command :as cmd]))


(defn recorder
  "A recording spawn: records each `{:opts :argv}` and returns the shared real `proc` so
   the head-dispatch's `process?` check fires on threaded stages."
  [calls* proc]
  (fn [opts argv]
    (swap! calls* conj {:opts opts :argv argv})
    proc))


;; --- value->tokens (value rules) -------------------------------------------

(deftest value->tokens-test
  (testing "scalars"
    (is (= []     (cmd/value->tokens nil)))
    (is (= ["hi"] (cmd/value->tokens "hi")))
    (is (= ["42"] (cmd/value->tokens 42)))
    (is (= ["x"]  (cmd/value->tokens 'x)))
    (is (= ["-n"] (cmd/value->tokens :-n))))

  (testing "keyword keeps a single slash via subs, not name"
    (is (= ["a/b"] (cmd/value->tokens :a/b))))

  (testing "sequential? splices recursively, drops nils"
    (is (= ["-o" "rw"]   (cmd/value->tokens [:-o "rw"])))
    (is (= ["a" "b" "c"] (cmd/value->tokens ["a" ["b" ["c"]]])))
    (is (= ["x"]         (cmd/value->tokens [nil "x" nil])))
    (is (= []            (cmd/value->tokens [nil nil]))))

  (testing "a Path stays ONE token via the (str v) catch-all (the sequential? trap)"
    (is (= ["/a/b"] (cmd/value->tokens (fs/path "/a" "b"))))
    (is (= ["/a/b"] (cmd/value->tokens [(fs/path "/a" "b")]))))

  (testing "map -> k=v pairs"
    (is (= ["if=/dev/sda"]    (cmd/value->tokens {:if "/dev/sda"})))
    (is (= ["--verbose"]      (cmd/value->tokens {:--verbose true})))
    (is (= []                 (cmd/value->tokens {:--quiet false :--x nil})))
    (is (= ["--color=always"] (cmd/value->tokens {:--color "always"})))
    (is (= ["bs=4M"]          (cmd/value->tokens {:bs "4M"}))))

  (testing "errors"
    (is (thrown-with-msg? Exception #"set"       (cmd/value->tokens #{1 2})))
    (is (thrown-with-msg? Exception #"boolean"   (cmd/value->tokens true)))
    (is (thrown-with-msg? Exception #"map value" (cmd/value->tokens {:k [1 2]})))))


(deftest map-value-path-test
  (testing "a Path/File map value glues like its string form, not just scalars"
    (is (= ["of=/a/b"] (cmd/value->tokens {:of (fs/path "/a" "b")})))
    (is (= (cmd/value->tokens {:of "/a/b"})
           (cmd/value->tokens {:of (fs/path "/a" "b")})))))


;; --- ->argv / sh* (the data runtime core) ----------------------------------

(deftest ->argv-test
  (testing "splices already-evaluated values via value->tokens, drops nils"
    (is (= ["git" "status"]         (cmd/->argv :git "status")))
    (is (= ["git" "status"]         (cmd/->argv "git" :status)))
    (is (= ["dd" "if=/x" "of=/y"]   (cmd/->argv :dd {:if "/x" :of "/y"})))
    (is (= ["git" "status"]         (cmd/->argv :git ["status"] nil nil nil)))
    (is (= ["tail" "-n" "100" "/x"] (cmd/->argv :tail :-n 100 ["/x"]))))

  (testing "a Path stays ONE token (the sequential? trap)"
    (is (= ["rm" "-rf" "/tmp/x"] (cmd/->argv :rm :-rf (fs/path "/tmp" "x"))))))


(deftest sh*-test
  (let [calls* (atom [])
        proc   (p/process "true")
        rec    (recorder calls* proc)]

    (testing "builds argv from data args and spawns with opts {}"
      (reset! calls* [])
      (cmd/sh* rec :git "status")
      (is (= [{:opts {} :argv ["git" "status"]}] @calls*)))

    (testing "a process cmd becomes a :prev pipe stage; the args are the command"
      (reset! calls* [])
      (cmd/sh* rec proc :grep "ujima")
      (let [{:keys [opts argv]} (first @calls*)]
        (is (= ["grep" "ujima"] argv))
        (is (cmd/process? (:prev opts)))))

    (testing "blank / empty argv[0] is rejected"
      (is (thrown-with-msg? Exception #"empty command" (cmd/sh* rec nil)))
      (is (thrown-with-msg? Exception #"empty command" (cmd/sh* rec ""))))))


;; --- $* argv lowering (recording spawn) ------------------------------------

(deftest dollar-star-test
  (let [calls* (atom [])
        proc   (p/process "true")
        rec    (recorder calls* proc)]

    (testing "bare symbols -> literal tokens, opts {}"
      (reset! calls* [])
      (cmd/$* rec echo hello)
      (is (= [{:opts {} :argv ["echo" "hello"]}] @calls*)))

    (testing "mixed literals: string / number / keyword"
      (reset! calls* [])
      (cmd/$* rec printf "%s" 42 :ok)
      (is (= [{:opts {} :argv ["printf" "%s" "42" "ok"]}] @calls*)))

    (testing "[expr] arg splices the evaluated value"
      (reset! calls* [])
      (let [path "/tmp/some path"]
        (cmd/$* rec cat [path]))
      (is (= [{:opts {} :argv ["cat" "/tmp/some path"]}] @calls*)))

    (testing "vector splice + ordered single-entry maps"
      (reset! calls* [])
      (cmd/$* rec dd {:if "/dev/sda"} {:of "/dev/sdb"} :bs=4M)
      (is (= [{:opts {} :argv ["dd" "if=/dev/sda" "of=/dev/sdb" "bs=4M"]}] @calls*)))

    (testing "bare -flag symbol and bare (expr) need no keyword or [] wrapping"
      (reset! calls* [])
      (cmd/$* rec rm -rf (fs/path "/tmp/x"))
      (is (= [{:opts {} :argv ["rm" "-rf" "/tmp/x"]}] @calls*)))

    (testing "runtime empty/blank argv[0] is rejected"
      (is (thrown-with-msg? Exception #"empty command"
            (cmd/$* rec [nil]))))))


;; --- piping head-dispatch (needs a real Process) ---------------------------

(deftest piping-test
  (testing "a threaded Process head becomes :prev; the next form is the command"
    (let [calls* (atom [])
          proc   (p/process "true")
          rec    (recorder calls* proc)]
      (cmd/$* rec (cmd/$* rec curl --fail ["/tmp/file"]) grep ujima)
      (is (= 2 (count @calls*)))
      (is (= {:opts {} :argv ["curl" "--fail" "/tmp/file"]} (first @calls*)))
      (let [{:keys [opts argv]} (second @calls*)]
        (is (= ["grep" "ujima"] argv))
        (is (cmd/process? (:prev opts)))))))


;; --- compile-time errors ---------------------------------------------------

(deftest compile-error-test
  (testing "literal set arg is rejected at macroexpand"
    (is (thrown-with-msg? Exception #"set"
          (macroexpand-1 '(lib.shell.command/$* rec echo #{1 2})))))

  (testing "literal boolean arg is rejected at macroexpand"
    (is (thrown-with-msg? Exception #"boolean"
          (macroexpand-1 '(lib.shell.command/$* rec echo true)))))

  (testing "empty $* is rejected"
    (is (thrown-with-msg? Exception #"requires a command"
          (macroexpand-1 '(lib.shell.command/$* rec))))))


;; --- $argv (dry-run) -------------------------------------------------------

(deftest dollar-argv-test
  (testing "returns {:cmd :opts}, no spawn"
    (is (= {:cmd ["git" "--oneline" "log"] :opts {}}
           (cmd/$argv git :--oneline log))))

  (testing "splices [expr] and lowers literals"
    (let [f "/x"]
      (is (= {:cmd ["tail" "-n" "100" "/x"] :opts {}}
             (cmd/$argv tail :-n 100 [f]))))))


;; --- $> redirect (terminal spawn, never remapped) --------------------------

(deftest redirect-test
  (testing "$> emits a `cat` stage carrying :prev and :out, through the terminal spawn"
    (let [calls* (atom [])
          proc   (p/process "true")
          prev   {:id 1}
          target "/tmp/ujima/image.img"]
      (with-redefs [cmd/spawn (recorder calls* proc)]
        (cmd/$> prev target))
      (is (= [{:opts {:prev prev :out (io/file target)} :argv ["cat"]}] @calls*)))))


(deftest leading-opts-map-test
  (testing "a leading map is spawn opts, not argv"
    (let [seen (atom nil)]
      (cmd/sh* (fn [opts argv] (reset! seen [opts argv]) :proc) {:in "42"} :cat)
      (is (= [{:in "42"} ["cat"]] @seen))))

  (testing "no map -> empty opts, argv unchanged"
    (let [seen (atom nil)]
      (cmd/sh* (fn [opts argv] (reset! seen [opts argv]) :proc) :echo "hi")
      (is (= [{} ["echo" "hi"]] @seen))))

  (testing "maps in argv positions still lower to k=v tokens"
    (let [seen (atom nil)]
      (cmd/sh* (fn [opts argv] (reset! seen [opts argv]) :proc) :dd {:if "/a" :of "/b"})
      (is (= [{} ["dd" "if=/a" "of=/b"]] @seen))))

  (testing "opts-only (no command) fails loud"
    (is (thrown? Exception (cmd/sh* (fn [_ _] :proc) {:in "42"}))))

  (testing "$argv reports a leading literal map as :opts"
    (is (= {:cmd ["cat"] :opts {:in "42"}} (cmd/$argv {:in "42"} cat)))
    (is (= {:cmd ["echo" "hi"] :opts {}}   (cmd/$argv echo hi)))))


(deftest piped-stage-opts-test
  (let [spawn (fn [opts argv] {:opts opts :argv argv})]
    (with-redefs [cmd/process? (fn [x] (= ::proc x))]
      (is (= {:opts {:prev ::proc :in "x"} :argv ["cat"]}
             (cmd/sh* spawn {:prev ::proc :in "x"} :cat))
          "opts on a piped stage: explicit :prev in the leading opts map")
      (is (= {:opts {:prev ::proc} :argv ["in=x" "cat"]}
             (cmd/sh* spawn ::proc {:in "x"} :cat))
          "a map AFTER a process is argv, not opts — argv[0] 'in=x' exec-fails loudly")
      (is (= {:opts {:prev ::proc} :argv ["dd" "if=/a"]}
             (cmd/sh* spawn ::proc :dd {:if "/a"}))
          "maps in argv positions after a pipe still lower to k=v"))))
