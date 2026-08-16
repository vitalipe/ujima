(ns ujima.events.audio-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.control          :as control]
            [ujima.control.commands :as commands]
            [ujima.events.audio :as events-audio]))


(deftest pick-active-policy
  (is (= :usb  (events-audio/pick-active #{:hdmi} #{:hdmi :usb} :hdmi))
      "a NEW class wins — plugging headphones means you want them")
  (is (= :hdmi (events-audio/pick-active #{:usb} #{:usb :hdmi} :usb))
      "newest wins even over an active choice")
  (is (= :hdmi (events-audio/pick-active #{:usb :hdmi} #{:hdmi} :usb))
      "vanished active falls back by class priority")
  (is (nil?  (events-audio/pick-active #{:usb} #{} :usb))
      "nothing present -> nil (widgets grey out)")
  (is (= :hdmi (events-audio/pick-active #{:usb :hdmi} #{:usb :hdmi} :hdmi))
      "baseline tick keeps a valid existing choice — ujimad restart must not re-decide")
  (is (= :usb  (events-audio/pick-active #{:usb :hdmi} #{:usb :hdmi} nil))
      "baseline without a choice -> priority order"))


(deftest on-sinks-changed-rewrites-active-even-when-unchanged
  ;; a same-class swap arrives as an event with equal class sets — the write
  ;; must still happen (its converge re-applies state onto the new device)
  (let [written (atom [])]
    (with-redefs [control/setting               (constantly {:effective :usb})
                  commands/change-active-output! (fn [v _scope] (swap! written conj v) {:output v})]
      (events-audio/on-sinks-changed! {:before #{:usb} :classes #{:usb}})
      (events-audio/on-sinks-changed! {:before #{:usb} :classes #{:usb :hdmi}}))
    (is (= [:usb :hdmi] @written)
        "swap re-asserts the same class; a new class wins")))
