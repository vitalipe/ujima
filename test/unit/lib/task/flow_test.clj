(ns lib.task.flow-test
  (:require [clojure.test :refer [deftest is]]
            [lib.task :as task]
            [lib.task.flow :refer [flow flow! <join! <step!]]))


(defn task-stub [name code]
  {:name name
   :code code})


(defn run-code!! [task]
  ((:code task) task))


(deftest flow-creates-cold-task-with-task*-bound-on-run
  (let [calls* (atom [])]

    (with-redefs [task/->task (fn [name code]
                                (let [task (task-stub name code)]
                                  (swap! calls* conj [:->task name])
                                  task))]

      (let [result (flow :install
                     (swap! calls* conj [:body (:name task*)]))]

        (is (= :install (:name result)))
        (is (= [[:->task :install]]
               @calls*))

        ((:code result) result)

        (is (= [[:->task :install]
                [:body :install]]
               @calls*))))))


(deftest flow!-creates-task-and-runs-it-synchronously
  (let [calls* (atom [])]

    (with-redefs [task/->task (fn [name code]
                                (task-stub name code))
                  task/run!! (fn [task]
                               (swap! calls* conj [:run!! (:name task)])
                               (run-code!! task)
                               :timeline)]

      (let [result (flow! :install
                     (swap! calls* conj [:body (:name task*)]))]

        (is (= :timeline result))
        (is (= [[:run!! :install]
                [:body :install]]
               @calls*))))))


(deftest flow!-progress!-maps-to-task-progress!
  (let [calls* (atom [])]

    (with-redefs [task/->task (fn [name code] (task-stub name code))
                  task/run!!  run-code!!

                  task/progress!
                  (fn
                    ([task progress]
                     (swap! calls* conj [:progress! (:name task) progress]))

                    ([task progress message]
                     (swap! calls* conj [:progress! (:name task) progress message])))]

      (flow! :install
        (progress! 10)
        (progress! 40 "partitioned"))

      (is (= [[:progress! :install 10]
              [:progress! :install 40 "partitioned"]]
             @calls*)))))


(deftest flow!-error!-with-type-and-message-throws-structured-ex-info
  (with-redefs [task/->task (fn [name code] (task-stub name code))
                task/run!!  run-code!!]

    (let [err (try
                (flow! :install
                  (error! :error/bad-disk "bad disk"))

                (catch clojure.lang.ExceptionInfo e
                  e))]

      (is (instance? clojure.lang.ExceptionInfo err))
      (is (= "bad disk" (ex-message err)))
      (is (= {:type :error/bad-disk}
             (ex-data err))))))


(deftest flow!-error!-with-throwable-rethrows-same-error
  (with-redefs [task/->task (fn [name code] (task-stub name code))
                task/run!!  run-code!!]

    (let [boom (ex-info "boom" {:x 1})
          err  (try
                 (flow! :install
                   (error! boom))

                 (catch Throwable e
                   e))]

      (is (identical? boom err)))))


(deftest <join!-evaluates-child-once-joins-and-returns-child
  (let [calls*      (atom [])
        eval-count* (atom 0)
        child       {:name :external-child}]

    (with-redefs [task/->task (fn [name code] (task-stub name code))
                  task/run!!  run-code!!

                  task/join!!
                  (fn [task child target-progress]
                    (swap! calls* conj [:join!! (:name task) (:name child) target-progress]))]

      (flow! :install
        (let [joined (<join! 90
                       (do
                         (swap! eval-count* inc)
                         child))]
          (swap! calls* conj [:joined joined])))

      (is (= 1 @eval-count*))

      (is (= [[:join!! :install :external-child 90]
              [:joined {:name :external-child}]]
             @calls*)))))


(deftest <step!-creates-child-flow-joins-it-and-returns-child
  (let [calls*  (atom [])
        next-id* (atom 0)]

    (with-redefs [task/->task (fn [name code]
                                {:id   (swap! next-id* inc)
                                 :name name
                                 :code code})

                  task/run!!  (fn [task]
                                (swap! calls* conj [:run!! (:id task)])
                                (run-code!! task))

                  task/progress!
                  (fn
                    ([task progress]
                     (swap! calls* conj [:progress! (:id task) progress]))

                    ([task progress message]
                     (swap! calls* conj [:progress! (:id task) progress message])))

                  task/join!!
                  (fn [task child target-progress]
                    (swap! calls* conj [:join!! (:id task) (:id child) target-progress])
                    (run-code!! child))]

      (let [root (flow! :install-root
                   (let [child (<step! 40 :partition-disk
                                 (progress! 20 "disk wiped")
                                 :child-result)]
                     (swap! calls* conj [:step-return (:id child)])
                     :root-result))]

        (is (= :root-result root))

        (is (= [[:run!! 1]
                [:join!! 1 2 40]
                [:progress! 2 20 "disk wiped"]
                [:step-return 2]]
               @calls*))))))


(deftest <step!-body-has-its-own-task*-binding
  (let [seen*   (atom [])
        next-id* (atom 0)]

    (with-redefs [task/->task (fn [name code]
                                {:id   (swap! next-id* inc)
                                 :name name
                                 :code code})

                  task/run!!  run-code!!

                  task/join!! (fn [_ child _]
                                (run-code!! child))]

      (flow! :parent
        (swap! seen* conj [:parent task*])

        (<step! 50 :child
          (swap! seen* conj [:child task*])))

      (is (= [[:parent 1]
              [:child 2]]
             (mapv (fn [[scope task]] [scope (:id task)]) @seen*))))))


(deftest <join!-uses-current-task*-binding
  (let [calls*  (atom [])
        next-id* (atom 0)]

    (with-redefs [task/->task (fn [name code]
                                {:id   (swap! next-id* inc)
                                 :name name
                                 :code code})

                  task/run!!  run-code!!

                  task/join!!
                  (fn [task child target-progress]
                    (swap! calls* conj [:join!! (:id task) (:id child) target-progress])
                    (when (:code child)
                      (run-code!! child)))]

      (flow! :parent
        (<step! 50 :child
          (<join! 25 {:id 999 :name :external})))

      (is (= [[:join!! 1 2 50]
              [:join!! 2 999 25]]
             @calls*)))))
