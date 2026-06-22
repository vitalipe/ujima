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
