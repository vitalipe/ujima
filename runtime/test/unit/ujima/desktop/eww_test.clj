(ns ujima.desktop.eww-test
  (:require [clojure.test :refer [deftest is]]
            [lib.shell :as shell]
            [ujima.desktop.eww :as eww]))


;; the current mode decides bar visibility and stamps :bars-hidden? on the snapshot;
;; eww just reads it (detection/solo logic lives in the modes' decorate, tested there).
(def ^:private shown  {:bars-hidden? false})
(def ^:private hidden {:bars-hidden? true})


(deftest show-bar?-reads-bars-hidden?
  (is (true?  (eww/show-bar? shown)))
  (is (true?  (eww/show-bar? {}))     "absent -> shown")
  (is (false? (eww/show-bar? hidden))))


(deftest converge!-debounces-and-coalesces-to-the-settled-state
  (reset! @#'eww/shown? true)
  (let [cmds  (atom [])
        quiet (+ @#'eww/debounce-ms 250)]
    (with-redefs [shell/sh? (fn [& args] (swap! cmds conj (some #{"open-many" "close"} args)) {:ok? true})]
      (eww/converge! shown  hidden)   ; show (no-op, already shown)
      (eww/converge! hidden shown)    ; hide
      (eww/converge! shown  hidden)   ; show
      (eww/converge! hidden shown)    ; final = hidden -> close
      (is (= [] @cmds) "nothing actuated mid-churn — debouncing")
      (Thread/sleep quiet)
      (is (= ["close"] @cmds) "coalesced to the settled state: one actuation"))))


(deftest every-client-call-refuses-to-daemonize
  (reset! @#'eww/shown? true)
  (let [args* (atom nil)
        quiet (+ @#'eww/debounce-ms 250)]
    (with-redefs [shell/sh? (fn [& args] (reset! args* (vec args)) {:ok? true})]
      (eww/converge! hidden shown)
      (Thread/sleep quiet)
      (is (some #{:--no-daemonize} @args*)
          "an eww client that cannot reach the daemon otherwise starts its OWN server"))))


(deftest converge!-does-not-latch-a-failed-flip
  (reset! @#'eww/shown? true)
  (let [calls (atom 0)
        quiet (+ @#'eww/debounce-ms 250)]
    (with-redefs [shell/sh? (fn [& _] (swap! calls inc) {:ok? false})]
      (eww/converge! hidden shown)
      (Thread/sleep quiet)
      (is (= 1 @calls))
      (is (true? @@#'eww/shown?) "a close that did not land must not be recorded as hidden")
      (eww/converge! hidden shown)
      (Thread/sleep quiet)
      (is (= 2 @calls) "still wants hidden — retries instead of latching a lie"))))


(deftest open-bars-or-throw!-trusts-the-daemon-not-the-exit-code
  (let [opens (atom 0)]
    (with-redefs [shell/sh? (fn [& args]
                              (if (some #{"active-windows"} args)
                                {:ok? true :out "topbar: topbar\ndock: dock\n"}
                                (do (swap! opens inc) {:ok? false})))]
      (is (true? (@#'eww/open-bars-or-throw! "/x")) "open-many reporting failure does not matter — the bars are up")
      (is (= 1 @opens) "no retry once the daemon reports both bars"))))


(deftest open-bars-or-throw!-gives-up-loudly-so-the-session-restarts
  (let [opens (atom 0)
        probe #'eww/probe-bars!
        orig  @probe]
    (try
      ;; stubbed to never settle — the real window is a boot's worth of patience
      (alter-var-root probe (constantly (constantly nil)))
      (with-redefs [shell/sh? (fn [& _] (swap! opens inc) {:ok? true})]
        (is (thrown? clojure.lang.ExceptionInfo (@#'eww/open-bars-or-throw! "/x"))
            "a desktop with no dock has no launcher — never carry on half-built")
        (is (= 3 @opens) "tried three times, then failed"))
      (finally (alter-var-root probe (constantly orig))))))
