(ns lib.shell-test
  (:require [clojure.test     :refer [deftest is testing]]
            [clojure.java.io  :as io]
            [babashka.fs      :as fs]
            [babashka.process :as p]
            [lib.shell        :as sh]
            [lib.shell.exec   :as exec]))


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
    (is (= []     (sh/value->tokens nil)))
    (is (= ["hi"] (sh/value->tokens "hi")))
    (is (= ["42"] (sh/value->tokens 42)))
    (is (= ["x"]  (sh/value->tokens 'x)))
    (is (= ["-n"] (sh/value->tokens :-n))))

  (testing "keyword keeps a single slash via subs, not name"
    (is (= ["a/b"] (sh/value->tokens :a/b))))

  (testing "sequential? splices recursively, drops nils"
    (is (= ["-o" "rw"]   (sh/value->tokens [:-o "rw"])))
    (is (= ["a" "b" "c"] (sh/value->tokens ["a" ["b" ["c"]]])))
    (is (= ["x"]         (sh/value->tokens [nil "x" nil])))
    (is (= []            (sh/value->tokens [nil nil]))))

  (testing "a Path stays ONE token via the (str v) catch-all (the sequential? trap)"
    (is (= ["/a/b"] (sh/value->tokens (fs/path "/a" "b"))))
    (is (= ["/a/b"] (sh/value->tokens [(fs/path "/a" "b")]))))

  (testing "map -> k=v pairs"
    (is (= ["if=/dev/sda"]    (sh/value->tokens {:if "/dev/sda"})))
    (is (= ["--verbose"]      (sh/value->tokens {:--verbose true})))
    (is (= []                 (sh/value->tokens {:--quiet false :--x nil})))
    (is (= ["--color=always"] (sh/value->tokens {:--color "always"})))
    (is (= ["bs=4M"]          (sh/value->tokens {:bs "4M"}))))

  (testing "errors"
    (is (thrown-with-msg? Exception #"set"       (sh/value->tokens #{1 2})))
    (is (thrown-with-msg? Exception #"boolean"   (sh/value->tokens true)))
    (is (thrown-with-msg? Exception #"map value" (sh/value->tokens {:k [1 2]})))))


;; --- $* argv lowering (recording spawn) ------------------------------------

(deftest dollar-lowering-test
  (let [calls* (atom [])
        proc   (p/process "true")
        rec    (recorder calls* proc)]

    (testing "bare symbols -> literal tokens, opts {}"
      (reset! calls* [])
      (sh/$* rec echo hello)
      (is (= [{:opts {} :argv ["echo" "hello"]}] @calls*)))

    (testing "mixed literals: string / number / keyword"
      (reset! calls* [])
      (sh/$* rec printf "%s" 42 :ok)
      (is (= [{:opts {} :argv ["printf" "%s" "42" "ok"]}] @calls*)))

    (testing "[expr] arg splices the evaluated value"
      (reset! calls* [])
      (let [path "/tmp/some path"]
        (sh/$* rec cat [path]))
      (is (= [{:opts {} :argv ["cat" "/tmp/some path"]}] @calls*)))

    (testing "vector splice + ordered single-entry maps"
      (reset! calls* [])
      (sh/$* rec dd {:if "/dev/sda"} {:of "/dev/sdb"} :bs=4M)
      (is (= [{:opts {} :argv ["dd" "if=/dev/sda" "of=/dev/sdb" "bs=4M"]}] @calls*)))

    (testing "bare -flag symbol and bare (expr) need no keyword or [] wrapping"
      (reset! calls* [])
      (sh/$* rec rm -rf (fs/path "/tmp/x"))
      (is (= [{:opts {} :argv ["rm" "-rf" "/tmp/x"]}] @calls*)))

    (testing "runtime empty/blank argv[0] is rejected"
      (is (thrown-with-msg? Exception #"empty command"
            (sh/$* rec [nil]))))))


;; --- piping head-dispatch (needs a real Process) ---------------------------

(deftest piping-test
  (testing "a threaded Process head becomes :prev; the next form is the command"
    ;; $* can't be threaded with -> (its first arg is the spawn), so nest explicitly:
    ;; the inner call's Process is placed in the outer head position.
    (let [calls* (atom [])
          proc   (p/process "true")
          rec    (recorder calls* proc)]
      (sh/$* rec (sh/$* rec curl --fail ["/tmp/file"]) grep ujima)
      (is (= 2 (count @calls*)))
      (is (= {:opts {} :argv ["curl" "--fail" "/tmp/file"]} (first @calls*)))
      (let [{:keys [opts argv]} (second @calls*)]
        (is (= ["grep" "ujima"] argv))
        (is (sh/process? (:prev opts))))))

  (testing "default $ pipes for real via -> through out-or-fail!"
    (is (= "hi" (-> (sh/$ echo "hi")
                    (sh/$ cat)
                    (exec/out-or-fail!))))))


;; --- compile-time errors ---------------------------------------------------

(deftest compile-error-test
  (testing "literal set arg is rejected at macroexpand"
    (is (thrown-with-msg? Exception #"set"
          (macroexpand-1 '(lib.shell/$* rec echo #{1 2})))))

  (testing "literal boolean arg is rejected at macroexpand"
    (is (thrown-with-msg? Exception #"boolean"
          (macroexpand-1 '(lib.shell/$* rec echo true)))))

  (testing "empty $* is rejected"
    (is (thrown-with-msg? Exception #"requires a command"
          (macroexpand-1 '(lib.shell/$* rec))))))


;; --- $argv (dry-run) -------------------------------------------------------

(deftest dollar-argv-test
  (testing "returns {:cmd :opts}, no spawn"
    (is (= {:cmd ["git" "--oneline" "log"] :opts {}}
           (sh/$argv git :--oneline log))))

  (testing "splices [expr] and lowers literals"
    (let [f "/x"]
      (is (= {:cmd ["tail" "-n" "100" "/x"] :opts {}}
             (sh/$argv tail :-n 100 [f]))))))


;; --- $! (run + check) ------------------------------------------------------

(deftest dollar-bang-test
  (testing "$! runs and returns trimmed stdout"
    (is (= "hi" (sh/$! echo "hi"))))

  (testing "$! throws on a non-zero exit"
    (is (thrown? Exception (sh/$! "false")))))


;; --- $> redirect -----------------------------------------------------------

(deftest redirect-test
  (testing "$>* emits a `cat` stage carrying :prev and :out"
    (let [calls* (atom [])
          proc   (p/process "true")
          rec    (recorder calls* proc)
          prev   {:id 1}
          target "/tmp/ujima/image.img"]
      (sh/$>* rec prev target)
      (is (= [{:opts {:prev prev :out (io/file target)} :argv ["cat"]}] @calls*)))))


;; --- exec finishers --------------------------------------------------------

(deftest finishers-test
  (testing "result! returns ok? false on non-zero, no throw"
    (let [r (exec/result! (p/process {:out :string :err :string} "false"))]
      (is (false? (:ok? r)))
      (is (pos? (:exit r)))))

  (testing "result! captures trimmed stdout"
    (let [r (exec/result! (p/process {:out :string :err :string} "echo" "hi"))]
      (is (true? (:ok? r)))
      (is (= "hi" (:out r)))))

  (testing "out-or-fail! throws on non-zero exit"
    (is (thrown? Exception (exec/out-or-fail! (sh/$ "false"))))))
