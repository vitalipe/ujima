(ns lib.io-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [lib.io :as io]))


(deftest slurp-config-merges-base-dev-local
  (fs/with-temp-dir [dir {}]
    (spit (str (fs/path dir "app.edn"))
          (pr-str {:a      1
                   :nested {:x    1
                            :keep true}}))
    (spit (str (fs/path dir "app.dev.edn"))
          (pr-str {:nested {:x        3
                            :override true}}))
    (spit (str (fs/path dir "app.local.edn"))
          (pr-str {:a      2
                   :nested {:x     2
                            :local true}}))

    ;; precedence: base < dev < local — local's :x wins (2, not dev's 3)
    (is (= {:a      2
            :nested {:x        2
                     :keep     true
                     :override true
                     :local    true}}
           (io/slurp-config (str dir) "app")))))


(deftest slurp-config-tolerates-missing-optional-files
  (fs/with-temp-dir [dir {}]
    (spit (str (fs/path dir "app.edn"))
          (pr-str {:a 1}))
    ;; no app.dev.edn / app.local.edn present
    (is (= {:a 1} (io/slurp-config (str dir) "app")))))
