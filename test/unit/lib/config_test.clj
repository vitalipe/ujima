(ns lib.config-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [lib.config :as config]))


(deftest init-loads-paths-left-to-right-and-applies-overrides-last
  (try
    (fs/with-temp-dir [dir {}]
      (let [base-path  (fs/path dir "base.edn")
            local-path (fs/path dir "local.edn")]
        (spit (str base-path)
              (pr-str {:a      1
                       :nested {:x    1
                                :keep true}}))
        (spit (str local-path)
              (pr-str {:a      2
                       :nested {:x     2
                                :local true}}))

        (config/init! [(str base-path)
                    (str local-path)]
                   {:nested {:x        3
                             :override true}})

        (is (= {:a      2
                :nested {:x        3
                         :keep     true
                         :local    true
                         :override true}}
               (config/env)))))
    (finally
      (config/init! [] {}))))
