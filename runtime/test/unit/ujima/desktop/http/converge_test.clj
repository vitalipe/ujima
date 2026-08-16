(ns ujima.desktop.http.converge-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.http.converge :as converge]))


(defn- ui [settings]
  (converge/settings->ui (update-vals settings #(hash-map :effective %))))


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
         (ui settings)))
  (is (= 70 (get-in (ui (assoc settings [:audio :active] :hdmi))
                    [:audio :volume]))
      "volume follows the active class"))


(deftest settings->ui-greys-out-without-an-active-output
  (is (= {:volume nil :muted false :output nil}
         (:audio (ui (assoc settings [:audio :active] nil))))))


(deftest settings->ui-carries-the-switcher-cycle
  (is (= "us" (get-in (ui (assoc settings [:keyboard :layout] "tz")) [:keyboard :next]))
      "the cycle itself is queries/next-keyboard-layout's contract — this is the wiring"))
