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
    (with-redefs [shell/sh? (fn [& args] (swap! cmds conj (nth (vec args) 3)) {:ok? true})]
      (eww/converge! shown  hidden)   ; show (no-op, already shown)
      (eww/converge! hidden shown)    ; hide
      (eww/converge! shown  hidden)   ; show
      (eww/converge! hidden shown)    ; final = hidden -> close
      (is (= [] @cmds) "nothing actuated mid-churn — debouncing")
      (Thread/sleep quiet)
      (is (= ["close"] @cmds) "coalesced to the settled state: one actuation"))))
