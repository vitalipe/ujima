(ns ujima.desktop.http.converge-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.http.converge :as converge]))


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
         (converge/settings->ui settings)))
  (is (= 70 (get-in (converge/settings->ui (assoc settings [:audio :active] :hdmi))
                    [:audio :volume]))
      "volume follows the active class"))


(deftest settings->ui-greys-out-without-an-active-output
  (is (= {:volume nil :muted false :output nil}
         (:audio (converge/settings->ui (assoc settings [:audio :active] nil))))))


(deftest settings->ui-computes-the-switcher-cycle
  (is (= "us" (get-in (converge/settings->ui (assoc settings [:keyboard :layout] "tz"))
                      [:keyboard :next]))
      "wraps")
  (is (= "us" (get-in (converge/settings->ui (assoc settings [:keyboard :layout] "il"))
                      [:keyboard :next]))
      "unknown current -> first")
  (is (nil? (get-in (converge/settings->ui (assoc settings [:keyboard :available-layouts] []))
                    [:keyboard :next]))
      "no layouts -> no next"))
