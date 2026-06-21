(ns ujima.shell-macro-test
  "Project-layer shell tests: command remap, sudo (remap-then-prepend), and the
   function-style sh/sudo API. The generic DSL/lowering behaviour lives in
   lib.shell-test."
  (:require [clojure.java.io   :as io]
            [clojure.test      :refer [deftest is testing]]
            [ujima.env         :as env]
            [lib.shell         :as lib]
            [ujima.linux.shell :as shell]))


(defn with-command-remap [remap f]
  (try
    (env/init! [] {:shell {:commands remap}})
    (f)
    (finally
      (env/init! [] {}))))


(defn recording-spawn
  "A spawn that records each `{:opts :argv}` and returns a derefable fake so the function
   API's `result!` finisher works."
  [calls* deref-val]
  (fn [opts argv]
    (swap! calls* conj {:opts opts :argv argv})
    (atom deref-val)))


;; --- remap-cmd / remap-argv (pure) -----------------------------------------

(deftest remap-cmd-test
  (with-command-remap {:cat ["echo" "cat"] :ls "/custom/ls"}
    (fn []
      (testing "keyword / symbol / string all resolve through the table"
        (is (= ["echo" "cat"] (shell/remap-cmd :cat)))
        (is (= ["echo" "cat"] (shell/remap-cmd 'cat)))
        (is (= ["echo" "cat"] (shell/remap-cmd "cat"))))

      (testing "a scalar fragment is one token; a vector fragment splices"
        (is (= ["/custom/ls"] (shell/remap-cmd :ls)))
        (is (= ["echo" "cat"] (shell/remap-cmd :cat))))

      (testing "unmapped token passes through"
        (is (= ["git"] (shell/remap-cmd :git))))

      (testing "a concrete path (contains '/') is never remapped"
        (is (= ["/bin/cat"] (shell/remap-cmd "/bin/cat")))))))


(deftest remap-argv-test
  (with-command-remap {:dd ["echo" "dd"]}
    (fn []
      (testing "remaps argv[0] only; the fragment splices, args are untouched"
        (is (= ["echo" "dd" "if=/x" "of=/y"]
               (shell/remap-argv ["dd" "if=/x" "of=/y"]))))

      (testing "an argument that matches a remap key is NOT remapped"
        (is (= ["cp" "dd"] (shell/remap-argv ["cp" "dd"])))))))


;; --- $ / sudo$ / $> remap (argv shape via recording spawn) -----------------

(deftest dollar-remap-test
  (testing "$ remaps argv[0]"
    (with-command-remap {:e2fsck ["echo" "e2fsck"]}
      (fn []
        (let [calls* (atom [])]
          (with-redefs [lib/spawn (recording-spawn calls* {:exit 0})]
            (shell/$ e2fsck -fn "/dev/x")
            (is (= [{:opts {} :argv ["echo" "e2fsck" "-fn" "/dev/x"]}] @calls*))))))))


(deftest sudo-dollar-remap-test
  (testing "sudo$ remaps the wrapped command AND sudo, then prepends sudo -n"
    (with-command-remap {:git ["/opt/git"] :sudo ["/opt/sudo"]}
      (fn []
        (let [calls* (atom [])]
          (with-redefs [lib/spawn (recording-spawn calls* {:exit 0})]
            (shell/sudo$ git status)
            (is (= [{:opts {} :argv ["/opt/sudo" "-n" "/opt/git" "status"]}] @calls*)))))))

  (testing "sudo$ splices multiword echo stubs for both sudo and the wrapped command"
    (with-command-remap {:dd ["echo" "dd"] :sudo ["echo" "sudo"]}
      (fn []
        (let [calls* (atom [])]
          (with-redefs [lib/spawn (recording-spawn calls* {:exit 0})]
            (shell/sudo$ dd "if=/x")
            (is (= [{:opts {} :argv ["echo" "sudo" "-n" "echo" "dd" "if=/x"]}] @calls*))))))))


(deftest redirect-remap-test
  (testing "$> remaps the cat stage and carries :prev / :out"
    (with-command-remap {:cat ["echo" "cat"]}
      (fn []
        (let [calls* (atom [])
              prev   {:id 1}
              target "/tmp/ujima/image.img"]
          (with-redefs [lib/spawn (recording-spawn calls* {:exit 0})]
            (shell/$> prev target)
            (is (= [{:opts {:prev prev :out (io/file target)} :argv ["echo" "cat"]}]
                   @calls*))))))))


;; --- function API: sh / sudo ----------------------------------------------

(deftest sh-test
  (testing "sh remaps, captures string output, returns a result map (no throw)"
    (with-command-remap {:e2fsck ["echo" "e2fsck"]}
      (fn []
        (let [calls* (atom [])]
          (with-redefs [lib/spawn (recording-spawn calls* {:exit 0 :out "ok\n" :err ""})]
            (is (= {:ok? true :exit 0 :out "ok" :err ""}
                   (shell/sh :e2fsck "-fn" "/dev/x")))
            (is (= [{:opts {:out :string :err :string :continue true}
                     :argv ["echo" "e2fsck" "-fn" "/dev/x"]}]
                   @calls*)))))))

  (testing "sh returns ok? false on a non-zero exit, does not throw"
    (with-command-remap {}
      (fn []
        (with-redefs [lib/spawn (recording-spawn (atom []) {:exit 1 :out "" :err "boom\n"})]
          (is (= {:ok? false :exit 1 :out "" :err "boom"}
                 (shell/sh :whatever))))))))


(deftest sudo-fn-test
  (testing "sudo remaps both sudo and the command, returns a result map"
    (with-command-remap {:sudo ["echo" "sudo"] :e2fsck ["echo" "e2fsck"]}
      (fn []
        (let [calls* (atom [])]
          (with-redefs [lib/spawn (recording-spawn calls* {:exit 0 :out "" :err ""})]
            (shell/sudo :e2fsck "-fn" "/dev/x")
            (is (= ["echo" "sudo" "-n" "echo" "e2fsck" "-fn" "/dev/x"]
                   (:argv (first @calls*))))))))))


;; --- end-to-end: real processes through remap + sudo (the bug fix) ----------

(deftest sudo-bang-echo-config-test
  (testing "sudo$! under echo stubs runs for real: wrapped command remapped + spliced"
    ;; With the dev echo-stub config, the launched argv is
    ;;   ["echo" "sudo" "-n" "echo" "dd" "if=/x"]
    ;; i.e. /bin/echo printing operands -> "sudo -n echo dd if=/x". This proves BOTH the
    ;; wrapped command remaps (dd -> echo dd) and the multiword values splice.
    (with-command-remap {:sudo ["echo" "sudo"] :dd ["echo" "dd"]}
      (fn []
        (is (= "sudo -n echo dd if=/x"
               (shell/sudo$! dd "if=/x"))))))

  (testing "$! under an echo stub runs for real and returns stdout"
    (with-command-remap {:tool ["echo"]}
      (fn []
        (is (= "hi there" (shell/$! tool "hi there")))))))


;; --- root? (ported unchanged) ----------------------------------------------

(deftest root?-test
  (testing "root? returns true for uid 0 without checking sudo"
    (let [calls* (atom [])]
      (with-redefs [shell/sh (fn [cmd & args]
                               (swap! calls* conj [cmd args])
                               (case cmd
                                 :id   {:ok? true :exit 0 :out "0\n" :err ""}
                                 :sudo (throw (ex-info "sudo should not be called" {}))))]
        (is (true? (shell/root?)))
        (is (= [[:id ["-u"]]]
               @calls*)))))

  (testing "root? falls back to passwordless sudo for non-root users"
    (let [calls* (atom [])]
      (with-redefs [shell/sh (fn [cmd & args]
                               (swap! calls* conj [cmd args])
                               (case cmd
                                 :id   {:ok? true :exit 0 :out "1000\n" :err ""}
                                 :sudo {:ok? true :exit 0 :out "" :err ""}))]
        (is (true? (shell/root?)))
        (is (= [[:id ["-u"]]
                [:sudo ["-n" "true"]]]
               @calls*)))))

  (testing "root? returns false when neither root nor passwordless sudo is available"
    (with-redefs [shell/sh (fn [cmd & _args]
                             (case cmd
                               :id   {:ok? true :exit 0 :out "1000\n" :err ""}
                               :sudo {:ok? false :exit 1 :out "" :err ""}))]
      (is (false? (shell/root?))))))
