(ns lib.io-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [lib.io :as io]))


(deftest env-deep-merges-base-local-dev-left-to-right
  (fs/with-temp-dir [dir {}]
    (spit (str (fs/path dir "app.edn"))
          (pr-str {:a      1
                   :nested {:x    1
                            :keep true}}))
    (spit (str (fs/path dir "app.local.edn"))
          (pr-str {:a      2
                   :nested {:x     2
                            :local true}}))
    (spit (str (fs/path dir "app.dev.edn"))
          (pr-str {:nested {:x        3
                            :override true}}))

    (is (= {:a      2
            :nested {:x        3
                     :keep     true
                     :local    true
                     :override true}}
           (io/slurp-config (str dir) "app")))))


(deftest env-tolerates-missing-optional-files
  (fs/with-temp-dir [dir {}]
    (spit (str (fs/path dir "app.edn"))
          (pr-str {:a 1}))
    ;; no app.local.edn / app.dev.edn present
    (is (= {:a 1} (io/slurp-config (str dir) "app")))))
