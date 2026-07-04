(ns ujima.desktop.ui-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.ui :as ui]))


(def ^:private settings
  {[:audio :active]                :usb
   [:audio :usb :volume]           55
   [:audio :hdmi :volume]          70
   [:audio :muted]                 false
   [:keyboard :layout]             "us"
   [:keyboard :available-layouts]  ["us" "tz"]})


(deftest settings->ui-projects-the-active-output
  (is (= {:audio    {:volume 55 :muted false :output :usb}
          :keyboard {:layout "us" :layouts ["us" "tz"] :next "tz"}}
         (ui/settings->ui settings)))
  (is (= 70 (get-in (ui/settings->ui (assoc settings [:audio :active] :hdmi))
                    [:audio :volume]))
      "volume follows the active class"))


(deftest settings->ui-greys-out-without-an-active-output
  (is (= {:volume nil :muted false :output nil}
         (:audio (ui/settings->ui (assoc settings [:audio :active] nil))))))


(deftest settings->ui-computes-the-switcher-cycle
  (is (= "us" (get-in (ui/settings->ui (assoc settings [:keyboard :layout] "tz"))
                      [:keyboard :next]))
      "wraps")
  (is (= "us" (get-in (ui/settings->ui (assoc settings [:keyboard :layout] "il"))
                      [:keyboard :next]))
      "unknown current -> first")
  (is (nil? (get-in (ui/settings->ui (assoc settings [:keyboard :available-layouts] []))
                    [:keyboard :next]))
      "no layouts -> no next"))
