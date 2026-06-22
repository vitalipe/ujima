(ns lib.shell-test
  "Context-layer tests: the dynamic `*spawn*`, command remap (sudo-aware), `with-spawn`/
   `with-remap`, and the `*spawn*`-backed `$`/`$!`/`$?` + `sh`/`sh!`/`sh?`. Engine/lowering
   behaviour lives in lib.shell.command-test."
  (:require [clojure.test     :refer [deftest is testing]]
            [babashka.process :as p]
            [lib.shell        :as sh]))


(defn recording-spawn
  "A spawn that records each `{:opts :argv}` and returns a derefable fake (so `result!`-style
   finishers work without launching a real process)."
  [calls* deref-val]
  (fn [opts argv]
    (swap! calls* conj {:opts opts :argv argv})
    (atom deref-val)))


;; --- command remap (sudo-aware, table-driven) ------------------------------

(deftest remap-argv-test
  (testing "remaps argv[0] only; the fragment splices, args are untouched"
    (is (= ["echo" "dd" "if=/x" "of=/y"]
           (sh/remap-argv {:dd ["echo" "dd"]} ["dd" "if=/x" "of=/y"])))
    (is (= ["cp" "dd"] (sh/remap-argv {:dd ["echo" "dd"]} ["cp" "dd"]))))

  (testing "a scalar fragment is one token"
    (is (= ["/custom/ls" "-l"] (sh/remap-argv {:ls "/custom/ls"} ["ls" "-l"]))))

  (testing "a concrete path (contains '/') is never remapped"
    (is (= ["/bin/cat"] (sh/remap-argv {:cat ["echo" "cat"]} ["/bin/cat"]))))

  (testing "unmapped token passes through"
    (is (= ["git" "status"] (sh/remap-argv {:dd ["echo" "dd"]} ["git" "status"]))))

  (testing "sudo-aware: skip a leading `sudo` + its -flags, remap the REAL command (not sudo)"
    (is (= ["sudo" "-n" "echo" "dd" "if=/x"]
           (sh/remap-argv {:dd ["echo" "dd"] :sudo ["echo" "sudo"]}
                          ["sudo" "-n" "dd" "if=/x"])))))


(deftest remapping+with-remap-test
  (testing "remapping decorates a spawn to remap argv before spawning"
    (let [calls* (atom [])]
      (((sh/remapping {:dd ["echo" "dd"]}) (recording-spawn calls* {:exit 0})) {} ["dd" "x"])
      (is (= ["echo" "dd" "x"] (:argv (first @calls*))))))

  (testing "with-remap composes remap onto the current *spawn*"
    (let [calls* (atom [])]
      (binding [sh/*spawn* (recording-spawn calls* {:exit 0})]
        (sh/with-remap {:dd ["echo" "dd"]} (sh/$ dd "x")))
      (is (= ["echo" "dd" "x"] (:argv (first @calls*)))))))


;; --- the *spawn*-backed convenience API ------------------------------------

(deftest dynamic-spawn-test
  (testing "$ runs through *spawn* (rebindable); opts {}"
    (let [calls* (atom [])]
      (binding [sh/*spawn* (recording-spawn calls* {:exit 0})]
        (sh/$ echo hi))
      (is (= [{:opts {} :argv ["echo" "hi"]}] @calls*))))

  (testing "$? wraps *spawn* with capture-opts and returns a result map (no throw)"
    (let [calls* (atom [])]
      (binding [sh/*spawn* (recording-spawn calls* {:exit 0 :out "ok\n" :err ""})]
        (is (= {:ok? true :exit 0 :out "ok" :err ""} (sh/$? echo "x"))))
      (is (= {:out :string :err :string :continue true} (:opts (first @calls*))))))

  (testing "$? returns ok? false on a non-zero exit, does not throw"
    (binding [sh/*spawn* (recording-spawn (atom []) {:exit 1 :out "" :err "boom\n"})]
      (is (= {:ok? false :exit 1 :out "" :err "boom"} (sh/$? whatever))))))


(deftest with-spawn-test
  (testing "with-spawn rebinds *spawn* for its body"
    (let [calls* (atom [])]
      (sh/with-spawn (recording-spawn calls* {:exit 0})
        (sh/$ echo hi))
      (is (= [{:opts {} :argv ["echo" "hi"]}] @calls*)))))


(deftest fn-forms-test
  (testing "sh runs through *spawn* with data args"
    (let [calls* (atom [])]
      (binding [sh/*spawn* (recording-spawn calls* {:exit 0})]
        (sh/sh :echo "hi"))
      (is (= [{:opts {} :argv ["echo" "hi"]}] @calls*))))

  (testing "sh? returns a result map with capture-opts"
    (let [calls* (atom [])]
      (binding [sh/*spawn* (recording-spawn calls* {:exit 0 :out "ok\n" :err ""})]
        (is (= {:ok? true :exit 0 :out "ok" :err ""} (sh/sh? :echo "x"))))
      (is (= {:out :string :err :string :continue true} (:opts (first @calls*)))))))


(deftest dollar-argv-test
  (testing "$argv (re-exported) dry-runs without spawning"
    (is (= {:cmd ["git" "--oneline" "log"] :opts {}} (sh/$argv git :--oneline log)))))


;; --- real runs through the default *spawn* (no remap installed in tests) ----

(deftest real-run-test
  (testing "$! runs and returns trimmed stdout"
    (is (= "hi" (sh/$! echo "hi"))))

  (testing "$! throws on a non-zero exit"
    (is (thrown? Exception (sh/$! "false"))))

  (testing "sh! (fn form) runs for real"
    (is (= "hi" (sh/sh! :echo "hi"))))

  (testing "$ pipes for real via -> through out-or-fail!"
    (is (= "hi" (-> (sh/$ echo "hi")
                    (sh/$ cat)
                    (sh/out-or-fail!))))))


;; --- finishers (re-exported from lib.shell.exec) ----------------------------

(deftest finishers-test
  (testing "result! returns ok? false on non-zero, no throw"
    (let [r (sh/result! (p/process {:out :string :err :string} "false"))]
      (is (false? (:ok? r)))
      (is (pos? (:exit r)))))

  (testing "result! captures trimmed stdout"
    (let [r (sh/result! (p/process {:out :string :err :string} "echo" "hi"))]
      (is (true? (:ok? r)))
      (is (= "hi" (:out r)))))

  (testing "out-or-fail! throws on non-zero exit"
    (is (thrown? Exception (sh/out-or-fail! (sh/$ "false"))))))


;; --- root? / install-remap! (mock *spawn*) ----------------------------------

(deftest root?-test
  (testing "uid 0 -> root, without probing sudo"
    (let [calls* (atom [])]
      (binding [sh/*spawn*
                (fn [_opts argv]
                  (swap! calls* conj argv)
                  (atom (case (first argv)
                          "id"   {:exit 0 :out "0\n" :err ""}
                          "sudo" (throw (ex-info "sudo should not be called" {})))))]
        (is (true? (sh/root?)))
        (is (= [["id" "-u"]] @calls*)))))

  (testing "non-root uid falls back to the passwordless sudo probe"
    (let [calls* (atom [])]
      (binding [sh/*spawn*
                (fn [_opts argv]
                  (swap! calls* conj argv)
                  (atom (case (first argv)
                          "id"   {:exit 0 :out "1000\n" :err ""}
                          "sudo" {:exit 0 :out "" :err ""})))]
        (is (true? (sh/root?)))
        (is (= [["id" "-u"] ["sudo" "-n" "true"]] @calls*)))))

  (testing "false when neither root nor passwordless sudo is available"
    (binding [sh/*spawn*
              (fn [_opts argv]
                (atom (case (first argv)
                        "id"   {:exit 0 :out "1000\n" :err ""}
                        "sudo" {:exit 1 :out "" :err ""})))]
      (is (false? (sh/root?))))))


(deftest install-remap!-test
  (testing "install-remap! makes the baseline *spawn* remap (verified via a real echo stub)"
    (let [orig lib.shell/*spawn*]
      (try
        (sh/install-remap! {:tool ["echo"]})
        (is (= "hi there" (sh/$! tool "hi there")))
        (finally
          (alter-var-root #'lib.shell/*spawn* (constantly orig)))))))
