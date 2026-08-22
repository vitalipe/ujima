(ns ujima.desktop.eww-test
  (:require [clojure.test :refer [deftest is]]
            [lib.shell :as shell]
            [ujima.desktop.eww :as eww]))


;; a stream/apps snapshot: current = the focused app entry (or nil)
(defn- snap [current] {:apps (if current [current] []) :current current})
(def ^:private launcher (snap nil))
(def ^:private tux-fs   (snap {:id :tuxtype :fullscreen true}))
(def ^:private tux-win  (snap {:id :tuxtype :fullscreen false}))


(deftest show-bar?-is-shown-unless-the-focused-window-is-fullscreen
  (is (true?  (eww/show-bar? launcher)) "launcher -> show")
  (is (true?  (eww/show-bar? tux-win))  "windowed app -> show")
  (is (false? (eww/show-bar? tux-fs))   "fullscreen -> hide"))

(deftest show-bar?-is-hidden-in-solo
  (is (false? (eww/show-bar? (assoc tux-win :mode :solo))) "solo -> hide, even a windowed P")
  (is (true?  (eww/show-bar? (assoc tux-win :mode :multi))) "multi windowed -> show"))


(deftest converge!-debounces-and-coalesces-to-the-settled-state
  (reset! @#'eww/shown? true)
  (let [cmds  (atom [])
        quiet (+ @#'eww/debounce-ms 250)]
    (with-redefs [shell/sh? (fn [& args] (swap! cmds conj (nth (vec args) 3)) {:ok? true})]
      (eww/converge! tux-win launcher)   ; show (no-op, already shown)
      (eww/converge! tux-fs  tux-win)    ; hide
      (eww/converge! tux-win tux-fs)     ; show
      (eww/converge! tux-fs  tux-win)    ; final = fullscreen -> hide
      (is (= [] @cmds) "nothing actuated mid-churn — debouncing")
      (Thread/sleep quiet)
      (is (= ["close"] @cmds) "coalesced to the settled state: one actuation"))))
