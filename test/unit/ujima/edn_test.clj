(ns ujima.edn-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.edn :as edn]))


(deftest edn->json-serializes-ordinary-throwables
  (let [result (-> {:error (Exception. "disk failed")}
                   (edn/edn->json)
                   (edn/json->edn))]

    (is (= {:error {:type "error/unexpected"
                    :message "disk failed"
                    :data {}}}
           result))))


(deftest edn->json-serializes-nested-task-errors
  (let [child-error  (ex-info "Write failed"
                              {:type :error/write-failed
                               :device "/dev/sda5"})
        parent-error (ex-info "Child task failed"
                              {:type :error/child-error
                               :child-error {:error child-error}})
        result       (-> {:payload {:error parent-error}}
                         (edn/edn->json)
                         (edn/json->edn))]

    (is (= {:payload
            {:error
             {:type "error/child-error"
              :message "Child task failed"
              :data
              {:child-error
               {:error
                {:type "error/write-failed"
                 :message "Write failed"
                 :data {:device "/dev/sda5"}}}}}}}
           result))))
