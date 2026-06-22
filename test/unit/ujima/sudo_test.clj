(ns ujima.sudo-test
  "Sudo layer: `with-sudo` composition, the sudo-aware remap baseline (sudo prefix skipped,
   wrapped command rewritten), and the `sudo`/`sudo?` fn + `sudo$*` macro sugar. Tests mock at
   the spawn level (bind `*spawn*`) — no real `sudo` is ever launched. (root?/require-root!/
   install-remap! moved to lib.shell — tested in lib.shell-test.)"
  (:require [clojure.test     :refer [deftest is testing]]
            [lib.shell        :as shell]
            [ujima.linux.sudo :as sudo]))


(defn recording-spawn
  "Records each `{:opts :argv}` and returns a derefable fake (so `result!` finishers work)."
  [calls* deref-val]
  (fn [opts argv]
    (swap! calls* conj {:opts opts :argv argv})
    (atom deref-val)))


;; --- with-sudo composition --------------------------------------------------

(deftest with-sudo-test
  (testing "with-sudo prepends `sudo -n` onto the current *spawn*"
    (let [calls* (atom [])]
      (binding [shell/*spawn* (recording-spawn calls* {:exit 0})]
        (sudo/with-sudo (shell/$ dd "if=/x")))
      (is (= [{:opts {} :argv ["sudo" "-n" "dd" "if=/x"]}] @calls*)))))


(deftest sudo-aware-remap-test
  (testing "with a remap baseline, with-sudo rewrites the WRAPPED command but not `sudo`"
    (let [calls* (atom [])
          base   (recording-spawn calls* {:exit 0})
          remap  ((shell/remapping {:dd ["echo" "dd"] :sudo ["echo" "sudo"]}) base)]
      (binding [shell/*spawn* remap]
        (sudo/sudo$ dd "if=/x"))
      ;; `sudo` is skipped by the sudo-aware remap (stays "sudo"); `dd` is rewritten:
      (is (= ["sudo" "-n" "echo" "dd" "if=/x"] (:argv (first @calls*)))))))


;; --- sudo fns / macros ------------------------------------------------------

(deftest sudo-fns-test
  (testing "sudo? captures + returns a result map; argv = sudo -n + remapped command"
    (let [calls* (atom [])
          base   (recording-spawn calls* {:exit 0 :out "ok\n" :err ""})
          remap  ((shell/remapping {:e2fsck ["echo" "e2fsck"]}) base)]
      (binding [shell/*spawn* remap]
        (is (= {:ok? true :exit 0 :out "ok" :err ""}
               (sudo/sudo? :e2fsck :-fn "/dev/x"))))
      (is (= ["sudo" "-n" "echo" "e2fsck" "-fn" "/dev/x"] (:argv (first @calls*))))
      (is (= {:out :string :err :string :continue true} (:opts (first @calls*))))))

  (testing "sudo$? macro form behaves the same"
    (let [calls* (atom [])]
      (binding [shell/*spawn* (recording-spawn calls* {:exit 0 :out "" :err ""})]
        (sudo/sudo$? e2fsck -fn "/dev/x"))
      (is (= ["sudo" "-n" "e2fsck" "-fn" "/dev/x"] (:argv (first @calls*)))))))
