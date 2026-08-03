(ns os.lib.i18n-test
  (:require [clojure.test :refer [deftest is]]
            [os.lib.i18n :as i18n]))


(defn- u32 [^bytes b off]
  (reduce + (map-indexed (fn [i n] (bit-shift-left (bit-and n 0xff) (* 8 i)))
                         (take 4 (drop off b)))))

(deftest mo-format-invariants
  (let [b (i18n/mo-bytes {"" "meta\n" "Home" "Temporary"})]
    (is (= 0x950412DE (u32 b 0)) "little-endian gettext magic")
    (is (= 2 (u32 b 8)) "entry count")
    (is (= 28 (u32 b 12)) "key table follows the header")
    (is (= (+ 28 (* 8 2)) (u32 b 16)) "value table follows the key table")
    ;; "" sorts first; its key is empty -> length 0 at the first key-table slot
    (is (= 0 (u32 b 28)))
    ;; every string is NUL-terminated and lengths exclude the NUL
    (is (= 0 (aget b (dec (alength b)))))))
