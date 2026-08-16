(ns ujima.control.queries-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.control.queries :as queries]))


(defn- records [m] (update-vals m #(hash-map :effective %)))


(deftest audio-status-follows-the-active-class
  (is (= {:volume 55 :muted false :output :usb}
         (queries/audio-status (records {[:audio :active]       :usb
                                         [:audio :usb :volume]  55
                                         [:audio :hdmi :volume] 70
                                         [:audio :muted]        false}))))
  (is (= {:volume nil :muted true :output nil}
         (queries/audio-status (records {[:audio :active]      nil
                                         [:audio :usb :volume] 55
                                         [:audio :muted]       true})))
      "no active output -> nil volume, widgets grey out"))


(deftest keyboard-status-is-domain-facts-only
  (is (= {:layout "tz" :layouts ["us" "tz"]}
         (queries/keyboard-status (records {[:keyboard :layout]            "tz"
                                            [:keyboard :available-layouts] ["us" "tz"]})))
      "no presentation fields — :next lives in the UI projection"))


(deftest settings->tree-nests-the-path-keys
  (is (= {:audio {:usb  {:volume {:effective 40}}
                  :muted        {:effective false}}
          :system {:hostname    {:effective "meru-01"}}}
         (queries/settings->tree (records {[:audio :usb :volume] 40
                                           [:audio :muted]       false
                                           [:system :hostname]   "meru-01"})))
      "[:audio :usb :volume] is three levels, not one"))
