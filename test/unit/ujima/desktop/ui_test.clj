(ns ujima.desktop.ui-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.ui :as ui]))


(def ^:private settings
  {[:audio :usb :volume]           55
   [:audio :hdmi :volume]          70
   [:audio :muted]                 false
   [:keyboard :layout]             "us"
   [:keyboard :available-layouts]  ["us" "tz"]})


(deftest settings->ui-projects-the-current-output-class
  (is (= {:audio    {:volume 55 :muted false :output :usb}
          :keyboard {:layout "us" :layouts ["us" "tz"] :next "tz"}}
         (ui/settings->ui settings :usb)))
  (is (= 70 (get-in (ui/settings->ui settings :hdmi) [:audio :volume]))
      "volume follows the output class"))


(deftest settings->ui-greys-out-without-an-output
  (is (= {:volume nil :muted false :output nil}
         (:audio (ui/settings->ui settings nil)))))


(deftest settings->ui-computes-the-switcher-cycle
  (is (= "us" (get-in (ui/settings->ui (assoc settings [:keyboard :layout] "tz") :usb)
                      [:keyboard :next]))
      "wraps")
  (is (= "us" (get-in (ui/settings->ui (assoc settings [:keyboard :layout] "il") :usb)
                      [:keyboard :next]))
      "unknown current -> first")
  (is (nil? (get-in (ui/settings->ui (assoc settings [:keyboard :available-layouts] []) :usb)
                    [:keyboard :next]))
      "no layouts -> no next"))
