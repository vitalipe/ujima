(ns ujima.desktop.eww-test
  (:require [clojure.test :refer [deftest is]]
            [lib.shell :as shell]
            [ujima.desktop.eww :as eww]))


;; a /ui/apps snapshot: current = the focused app entry (or nil)
(defn- snap [current] {:apps (if current [current] []) :current current})
(def ^:private launcher (snap nil))
(def ^:private tux-fs   (snap {:id :tuxtype :fullscreen true}))
(def ^:private tux-win  (snap {:id :tuxtype :fullscreen false}))


(deftest show-bar?-is-a-pure-latch-over-next+prv
  (is (true?  (eww/show-bar? launcher launcher true))  "launcher -> show")
  (is (true?  (eww/show-bar? tux-win  launcher true))  "windowed app -> show")
  (is (false? (eww/show-bar? tux-fs   launcher true))  "fullscreen -> hide")
  (is (false? (eww/show-bar? tux-win  tux-fs   false)) "same app flap, were hidden -> stay hidden")
  (is (false? (eww/show-bar? tux-fs   tux-win  false)) "flap back to fullscreen -> hidden")
  (is (true?  (eww/show-bar? launcher tux-fs   false)) "focus left -> show"))


(deftest converge!-actuates-eww-only-on-a-visibility-flip
  (reset! @#'eww/shown? true)
  (let [calls (atom [])]
    (with-redefs [shell/sh? (fn [& args] (swap! calls conj (nth (vec args) 3)) {:ok? true})]
      (eww/converge! tux-fs  launcher)   ; fullscreen -> hide (close)
      (eww/converge! tux-win tux-fs)     ; same app windowed -> latched, no eww call
      (eww/converge! tux-fs  tux-win)    ; flap fullscreen -> latched, no eww call
      (is (= ["close"] @calls) "hidden once; the app's own flapping never re-actuates")
      (eww/converge! launcher tux-fs)    ; focus leaves -> show (open-many)
      (is (= ["close" "open-many"] @calls) "reopened only when focus left the app"))))
