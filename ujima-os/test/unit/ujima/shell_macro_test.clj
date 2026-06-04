(ns ujima.shell-macro-test
  (:require [clojure.java.io  :as io]
            [clojure.test     :refer [deftest is testing]]
            [babashka.process :as process]
            [ujima.env        :as env]
            [ujima.linux.shell :as shell]))


(defn with-command-remap [remap f]
  (try
    (env/init! [] {:shell {:commands remap}})
    (f)
    (finally
      (env/init! [] {}))))


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

        (is (= [["printf" "%s" "42" "ok"]]
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


(deftest redirect-dollar-macro-test
  (testing "$> redirects previous process stdout to a file path using cat"
    (let [calls* (atom [])
          prev   {:id 1}
          target "/tmp/ujima/image.img"]
      (with-redefs [process/process (fn [& args]
                                      (swap! calls* conj args)
                                      {:proc true})]
        (is (= {:proc true}
               (shell/$> prev target)))

        (is (= [[{:prev prev
                  :out  (io/file target)}
                 "cat"]]
               @calls*)))))

  (testing "$> works in thread-first pipelines"
    (let [calls* (atom [])
          n*     (atom 0)
          target "/tmp/ujima/image.img"]
      (with-redefs [process/process (fn [& args]
                                      (let [proc {:id   (swap! n* inc)
                                                  :args args}]
                                        (swap! calls* conj args)
                                        proc))]
        (let [result (-> (shell/$ curl --fail --location ["https://example.test/os.img.xz"])
                         (shell/$ xz -dc)
                         (shell/$> target))]
          (is (= {:id   3
                  :args [{:prev {:id   2
                                 :args [{:prev {:id   1
                                                :args ["curl"
                                                       "--fail"
                                                       "--location"
                                                       "https://example.test/os.img.xz"]}}
                                        "xz"
                                        "-dc"]}
                         :out  (io/file target)}
                         "cat"]}
                 result))

          (is (= [["curl" "--fail" "--location" "https://example.test/os.img.xz"]
                  [{:prev {:id   1
                           :args ["curl"
                                  "--fail"
                                  "--location"
                                  "https://example.test/os.img.xz"]}}
                   "xz"
                   "-dc"]
                  [{:prev {:id   2
                           :args [{:prev {:id   1
                                          :args ["curl"
                                                 "--fail"
                                                 "--location"
                                                 "https://example.test/os.img.xz"]}}
                                  "xz"
                                  "-dc"]}
                   :out  (io/file target)}
                   "cat"]]
                 @calls*))))))

  (testing "$> accepts evaluated target expressions through normal Clojure syntax"
    (let [calls*    (atom [])
          prev      {:id 1}
          stage-dir "/tmp/ujima-stage"
          filename  "image.img"]
      (with-redefs [process/process (fn [& args]
                                      (swap! calls* conj args)
                                      {:proc true})]
        (shell/$> prev (str stage-dir "/" filename))

        (is (= [[{:prev prev
                  :out  (io/file "/tmp/ujima-stage/image.img")}
                 "cat"]]
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
      (is (= ["printf" "%s" "42" "ok"]
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


(deftest command-remap-test
  (testing "re-map->cmd maps keywords, symbols, and strings"
    (with-command-remap
      {:cat "/custom/cat"}
      (fn []
        (is (= "/custom/cat"
               (shell/re-map->cmd :cat)))
        (is (= "/custom/cat"
               (shell/re-map->cmd 'cat)))
        (is (= "/custom/cat"
               (shell/re-map->cmd "cat"))))))

  (testing "re-map->cmd does not remap concrete paths"
    (with-command-remap
      {:cat "/custom/cat"}
      (fn []
        (is (= "/bin/cat"
               (shell/re-map->cmd "/bin/cat"))))))

  (testing "sh remaps the executed command"
    (with-command-remap
      {:e2fsck "/custom/e2fsck"}
      (fn []
        (let [calls* (atom [])]
          (with-redefs [process/shell (fn [& args]
                                        (swap! calls* conj args)
                                        {:exit 0 :out "" :err ""})]
            (shell/sh :e2fsck "-fn" "/dev/x")
            (is (= [[{:out :string
                      :err :string
                      :continue true}
                     "/custom/e2fsck"
                     "-fn"
                     "/dev/x"]]
                   @calls*)))))))

  (testing "sudo remaps both sudo and the delegated command"
    (with-command-remap
      {:sudo "/custom/sudo"
       :e2fsck "/custom/e2fsck"}
      (fn []
        (let [calls* (atom [])]
          (with-redefs [process/shell (fn [& args]
                                        (swap! calls* conj args)
                                        {:exit 0 :out "" :err ""})]
            (shell/sudo :e2fsck "-fn" "/dev/x")
            (is (= [[{:out :string
                      :err :string
                      :continue true}
                     "/custom/sudo"
                     "-n"
                     "/custom/e2fsck"
                     "-fn"
                     "/dev/x"]]
                   @calls*)))))))

  (testing "sudo! remaps both sudo and the delegated command"
    (with-command-remap
      {:sudo "/custom/sudo"
       :e2fsck "/custom/e2fsck"}
      (fn []
        (let [calls* (atom [])]
          (with-redefs [process/shell (fn [& args]
                                        (swap! calls* conj args)
                                        {:exit 0 :out "ok\n" :err ""})]
            (is (= "ok"
                   (shell/sudo! :e2fsck "-fn" "/dev/x")))
            (is (= [[{:out :string
                      :err :string
                      :continue true}
                     "/custom/sudo"
                     "-n"
                     "/custom/e2fsck"
                     "-fn"
                     "/dev/x"]]
                   @calls*)))))))

  (testing "$ remaps process commands and preserves :prev pipelines"
    (with-command-remap
      {:curl "/custom/curl"
       :grep "/custom/grep"}
      (fn []
        (let [calls* (atom [])
              n*     (atom 0)]
          (with-redefs [process/process (fn [& args]
                                          (let [proc {:id   (swap! n* inc)
                                                      :args args}]
                                            (swap! calls* conj args)
                                            proc))]
            (-> (shell/$ curl --fail ["/tmp/file"])
                (shell/$ grep ujima))
            (is (= [["/custom/curl" "--fail" "/tmp/file"]
                    [{:prev {:id   1
                             :args ["/custom/curl" "--fail" "/tmp/file"]}}
                     "/custom/grep"
                     "ujima"]]
                   @calls*)))))))

  (testing "$> remaps the redirect cat command"
    (with-command-remap
      {:cat "/custom/cat"}
      (fn []
        (let [calls* (atom [])
              prev   {:id 1}
              target "/tmp/ujima/image.img"]
          (with-redefs [process/process (fn [& args]
                                          (swap! calls* conj args)
                                          {:proc true})]
            (shell/$> prev target)
            (is (= [[{:prev prev
                      :out  (io/file target)}
                     "/custom/cat"]]
                   @calls*))))))))


(deftest root?-test
  (testing "root? returns true for uid 0 without checking sudo"
    (let [calls* (atom [])]
      (with-redefs [shell/sh (fn [cmd & args]
                               (swap! calls* conj [cmd args])
                               (case cmd
                                 :id {:ok? true :exit 0 :out "0\n" :err ""}
                                 :sudo (throw (ex-info "sudo should not be called" {}))))]
        (is (true? (shell/root?)))
        (is (= [[:id ["-u"]]]
               @calls*)))))

  (testing "root? falls back to passwordless sudo for non-root users"
    (let [calls* (atom [])]
      (with-redefs [shell/sh (fn [cmd & args]
                               (swap! calls* conj [cmd args])
                               (case cmd
                                 :id {:ok? true :exit 0 :out "1000\n" :err ""}
                                 :sudo {:ok? true :exit 0 :out "" :err ""}))]
        (is (true? (shell/root?)))
        (is (= [[:id ["-u"]]
                [:sudo ["-n" "true"]]]
               @calls*)))))

  (testing "root? returns false when neither root nor passwordless sudo is available"
    (with-redefs [shell/sh (fn [cmd & _args]
                             (case cmd
                               :id {:ok? true :exit 0 :out "1000\n" :err ""}
                               :sudo {:ok? false :exit 1 :out "" :err ""}))]
      (is (false? (shell/root?))))))


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
