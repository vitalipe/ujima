(ns ujima.task.flow-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.task :as task]
            [ujima.task.flow :refer [flow! <join! <step!]]))


(deftest flow!-creates-task-and-runs-body-with-task*
  (let [calls* (atom [])]

    (with-redefs [task/->task (fn [name]
                                (let [task {:name name}]
                                  (swap! calls* conj [:->task name])
                                  task))

                  task/run!  (fn [task f]
                               (swap! calls* conj [:run! task])
                               (f task)
                               task)]

      (let [result (flow! :install
                     (swap! calls* conj [:body task*]))]

        (is (= {:name :install}
               result))

        (is (= [[:->task :install]
                [:run! {:name :install}]
                [:body {:name :install}]]
               @calls*))))))


(deftest flow!-progress!-maps-to-task-progress!
  (let [calls* (atom [])]

    (with-redefs [task/->task (fn [name] {:name name})
                  task/run!   (fn [task f] (f task) task)

                  task/progress!
                  (fn
                    ([task progress]
                     (swap! calls* conj [:progress! task progress]))

                    ([task progress message]
                     (swap! calls* conj [:progress! task progress message])))]

      (flow! :install
        (progress! 10)
        (progress! 40 "partitioned"))

      (is (= [[:progress! {:name :install} 10]
              [:progress! {:name :install} 40 "partitioned"]]
             @calls*)))))


(deftest flow!-error!-with-type-and-message-throws-structured-ex-info
  (with-redefs [task/->task (fn [name] {:name name})
                task/run!   (fn [task f] (f task) task)]

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
  (with-redefs [task/->task (fn [name] {:name name})
                task/run!   (fn [task f] (f task) task)]

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

    (with-redefs [task/->task (fn [name] {:name name})
                  task/run!   (fn [task f] (f task) task)

                  task/join!!
                  (fn [task child target-progress]
                    (swap! calls* conj [:join!! task child target-progress]))]

      (flow! :install
        (let [joined (<join! 90
                       (do
                         (swap! eval-count* inc)
                         child))]
          (swap! calls* conj [:joined joined])))

      (is (= 1 @eval-count*))

      (is (= [[:join!! {:name :install} {:name :external-child} 90]
              [:joined {:name :external-child}]]
             @calls*)))))


(deftest <step!-creates-child-flow-joins-it-and-returns-child
  (let [calls*  (atom [])
        next-id* (atom 0)]

    (with-redefs [task/->task (fn [name]
                                {:id   (swap! next-id* inc)
                                 :name name})

                  task/run!   (fn [task f]
                                (swap! calls* conj [:run! task])
                                (f task)
                                task)

                  task/progress!
                  (fn
                    ([task progress]
                     (swap! calls* conj [:progress! task progress]))

                    ([task progress message]
                     (swap! calls* conj [:progress! task progress message])))

                  task/join!!
                  (fn [task child target-progress]
                    (swap! calls* conj [:join!! task child target-progress]))]

      (let [root (flow! :install-root
                   (let [child (<step! 40 :partition-disk
                                 (progress! 20 "disk wiped")
                                 :child-result)]
                     (swap! calls* conj [:step-return child])
                     :root-result))]

        (is (= {:id 1 :name :install-root}
               root))

        (is (= [[:run! {:id 1 :name :install-root}]
                [:run! {:id 2 :name :partition-disk}]
                [:progress! {:id 2 :name :partition-disk} 20 "disk wiped"]
                [:join!! {:id 1 :name :install-root} {:id 2 :name :partition-disk} 40]
                [:step-return {:id 2 :name :partition-disk}]]
               @calls*))))))


(deftest <step!-body-has-its-own-task*-binding
  (let [seen*   (atom [])
        next-id* (atom 0)]

    (with-redefs [task/->task (fn [name]
                                {:id   (swap! next-id* inc)
                                 :name name})

                  task/run!   (fn [task f]
                                (f task)
                                task)

                  task/join!! (fn [_ _ _] nil)]

      (flow! :parent
        (swap! seen* conj [:parent task*])

        (<step! 50 :child
          (swap! seen* conj [:child task*])))

      (is (= [[:parent {:id 1 :name :parent}]
              [:child {:id 2 :name :child}]]
             @seen*)))))


(deftest <join!-uses-current-task*-binding
  (let [calls*  (atom [])
        next-id* (atom 0)]

    (with-redefs [task/->task (fn [name]
                                {:id   (swap! next-id* inc)
                                 :name name})

                  task/run!   (fn [task f]
                                (f task)
                                task)

                  task/join!!
                  (fn [task child target-progress]
                    (swap! calls* conj [:join!! task child target-progress]))]

      (flow! :parent
        (<step! 50 :child
          (<join! 25 {:id 999 :name :external})))

      (is (= [[:join!! {:id 2 :name :child} {:id 999 :name :external} 25]
              [:join!! {:id 1 :name :parent} {:id 2 :name :child} 50]]
             @calls*)))))