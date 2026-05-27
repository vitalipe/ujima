(ns ujima.shell-macro-test
  (:require [clojure.test      :refer [deftest is testing]]
            [babashka.process :as process]
            [ujima.linux.shell :as shell]))


(deftest dollar-macro-test
  (testing "$ converts symbols to argv tokens and calls babashka.process/process"
    (let [calls* (atom [])]
      (with-redefs [process/process (fn [& args]
                                      (swap! calls* conj args)
                                      {:proc true})]
        (is (= {:proc true}
               (shell/$ echo hello)))

        (is (= [["echo" "hello"]]
               @calls*)))))

  (testing "$ stringifies literal non-symbol args"
    (let [calls* (atom [])]
      (with-redefs [process/process (fn [& args]
                                      (swap! calls* conj args)
                                      {:proc true})]
        (shell/$ printf "%s" 42 :ok)

        (is (= [["printf" "%s" "42" ":ok"]]
               @calls*)))))

  (testing "$ evaluates [expr] args and stringifies the result"
    (let [calls* (atom [])]
      (with-redefs [process/process (fn [& args]
                                      (swap! calls* conj args)
                                      {:proc true})]
        (let [path "/tmp/some path"]
          (shell/$ cat [path]))

        (is (= [["cat" "/tmp/some path"]]
               @calls*)))))

  (testing "$ supports thread-first process piping using :prev"
    (let [calls* (atom [])
          n*     (atom 0)]
      (with-redefs [process/process (fn [& args]
                                      (let [proc {:id   (swap! n* inc)
                                                  :args args}]
                                        (swap! calls* conj args)
                                        proc))]
        (let [result (-> (shell/$ curl --fail ["/tmp/file"])
                         (shell/$ grep ujima))]
          (is (= {:id   2
                  :args [{:prev {:id   1
                                 :args ["curl" "--fail" "/tmp/file"]}}
                         "grep"
                         "ujima"]}
                 result))

          (is (= [["curl" "--fail" "/tmp/file"]
                  [{:prev {:id   1
                           :args ["curl" "--fail" "/tmp/file"]}}
                   "grep"
                   "ujima"]]
                 @calls*)))))))


(deftest sudo-dollar-macro-test
  (testing "sudo$ prefixes command with sudo -n"
    (let [calls* (atom [])]
      (with-redefs [process/process (fn [& args]
                                      (swap! calls* conj args)
                                      {:proc true})]
        (let [device "/dev/sdz"]
          (shell/sudo$ wipefs -a [device]))

        (is (= [["sudo" "-n" "wipefs" "-a" "/dev/sdz"]]
               @calls*)))))

  (testing "sudo$ supports thread-first process piping using :prev"
    (let [calls* (atom [])
          n*     (atom 0)]
      (with-redefs [process/process (fn [& args]
                                      (let [proc {:id   (swap! n* inc)
                                                  :args args}]
                                        (swap! calls* conj args)
                                        proc))]
        (-> (shell/$ echo hello)
            (shell/sudo$ tee ["/root/file"]))

        (is (= [["echo" "hello"]
                [{:prev {:id   1
                         :args ["echo" "hello"]}}
                 "sudo"
                 "-n"
                 "tee"
                 "/root/file"]]
               @calls*))))))


(deftest dollar-bang-macro-test
  (testing "$! converts tokens and delegates to sh!"
    (with-redefs [shell/sh! (fn [& args] (vec args))]
      (is (= ["echo" "hello"]
             (shell/$! echo hello)))))

  (testing "$! evaluates [expr] args and stringifies the result"
    (with-redefs [shell/sh! (fn [& args] (vec args))]
      (let [path "/tmp/some path"]
        (is (= ["cat" "/tmp/some path"]
               (shell/$! cat [path]))))))

  (testing "$! stringifies literal non-symbol args"
    (with-redefs [shell/sh! (fn [& args] (vec args))]
      (is (= ["printf" "%s" "42" ":ok"]
             (shell/$! printf "%s" 42 :ok))))))


(deftest sudo-dollar-bang-macro-test
  (testing "sudo$! converts tokens and delegates to sudo!"
    (with-redefs [shell/sudo! (fn [& args] (vec args))]
      (let [device "/dev/sdz"]
        (is (= ["wipefs" "-a" "/dev/sdz"]
               (shell/sudo$! wipefs -a [device]))))))

  (testing "sudo$! stringifies literal non-symbol args"
    (with-redefs [shell/sudo! (fn [& args] (vec args))]
      (is (= ["install" "-m" "644" "src" "dst"]
             (shell/sudo$! install -m 644 "src" "dst"))))))


(deftest macro-validation-test
  (testing "empty [expr] is rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"cannot be empty"
          (macroexpand-1
            '(ujima.linux.shell/$ echo [])))))

  (testing "multi-expression [expr] is rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"exactly one expression"
          (macroexpand-1
            '(ujima.linux.shell/$ echo [a b])))))

  (testing "raw Clojure forms are rejected as command args"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Use \[\.\.\.\]"
          (macroexpand-1
            '(ujima.linux.shell/$ echo (+ 1 2))))))

  (testing "missing command is rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"requires a command"
          (macroexpand-1
            '(ujima.linux.shell/$))))))