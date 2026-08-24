(ns ujima.linux.devicetree
  "The firmware's hardware description, read as /proc/device-tree files — ARM
   SBCs have one; machines without (x86 dev hosts) read nil."
  (:require [clojure.string :as str]
            [lib.io :refer [slurp-text]]))


;; devicetree strings are NUL-terminated
(defn- prop [name]
  (some-> (slurp-text (str "/proc/device-tree/" name) nil)
          (str/replace (str (char 0)) "")
          str/trim
          not-empty))


(defn serial [] (prop "serial-number"))
(defn model  [] (prop "model"))

(defn serial-tail
  "The serial's last 4 characters — the machine's short id (nil off-devicetree)."
  []
  (when-let [s (serial)]
    (subs s (max 0 (- (count s) 4)))))
