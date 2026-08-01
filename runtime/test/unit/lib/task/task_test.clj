(ns lib.task.task-test
  (:require [clojure.test :refer [deftest is testing]]
            [lib.task :as task]
            [lib.task.timeline :as timeline]))


(defn events-for-id [t id]
  (->> (task/task->timeline t)
       (filter #(= id (:id %)))
       (into [])))


(defn task-events [t]
  (events-for-id t (:id t)))


(defn event-types [events]
  (->> events
       (map :type)
       (into [])))


(defn progress-events [t]
  (->> (task-events t)
       (filter #(= :progress (:type %)))
       (into [])))


(defn test-task
  ([name]
   (test-task name (fn [_] nil)))
  ([name code]
   (task/->task name code)))


(defn start-task! [t]
  (task/event! t
               (timeline/->TimelineEvent (:id t)
                                         [(:name t)]
                                         :started
                                         nil
                                         {})))


(defn wait-finished [t]
  (loop [n 100]
    (cond
      (task/finished? t) true
      (pos? n)           (do
                           (Thread/sleep 10)
                           (recur (dec n)))
      :otherwise         false)))


(deftest ->task-creates-empty-task
  (let [t (test-task :install)]

    (is (= :install (:name t)))
    (is (integer? (:id t)))
    (is (= [] (task/task->timeline t)))
    (is (false? (task/finished? t)))))


(deftest progress!-records-clamped-rounded-progress
  (let [t (test-task :install)]

    (task/progress! t -10 "too low")
    (task/progress! t 42.6 "middle")
    (task/progress! t 200 "too high")

    (is (= [0 43 100]
           (->> (progress-events t)
                (map #(get-in % [:payload :progress]))
                (into []))))

    (is (= ["too low" "middle" "too high"]
           (->> (progress-events t)
                (map #(get-in % [:payload :message]))
                (into []))))))


(deftest progress!-supports-message-less-progress
  (let [t (test-task :install)
        e (task/progress! t 25)]

    (is (= :progress (:type e)))
    (is (= {:message nil
            :progress 25}
           (:payload e)))))


(deftest event!-publishes-to-channel
  (let [t (test-task :install)
        e (task/progress! t 10 "started")]

    (is (= e (task/take!! t)))))


(deftest done!-records-terminal-event-and-closes-channel
  (let [t (test-task :install)
        e (task/done! t {:ok? true})]

    (is (= :done (:type e)))
    (is (= {:ok? true} (:payload e)))
    (is (task/finished? t))

    ;; The terminal event was buffered before close.
    (is (= e (task/take!! t)))

    ;; Then the closed channel returns nil.
    (is (nil? (task/take!! t)))))


(deftest error!-records-terminal-event-and-message
  (let [t   (test-task :install)
        err (ex-info "boom" {:x 1})
        e   (task/error! t err "install failed")]

    (is (= :error (:type e)))
    (is (= err (:error (:payload e))))
    (is (= "install failed" (:message (:payload e))))
    (is (task/finished? t))))


(deftest error!-uses-default-message
  (let [t   (test-task :install)
        err (ex-info "boom" {})
        e   (task/error! t err)]

    (is (= :error (:type e)))
    (is (= "task failed" (:message (:payload e))))))


(deftest terminal-event-prevents-later-events-for-task
  (let [t        (test-task :install)
        done     (task/done! t {:ok? true})
        rejected (task/progress! t 50 "should not append")]

    (is done)
    (is (nil? rejected))
    (is (= [:done]
           (event-types (task-events t))))))


(deftest run!!-records-started-and-done
  (let [t        (test-task :install
                            (fn [_]
                              {:installed? true}))
        timeline (task/run!! t)]

    (is (= [:started :done]
           (event-types (task-events t))))

    (is (= [:started :done]
           (event-types timeline)))

    (is (= {:installed? true}
           (:payload (last timeline))))

    (is (task/finished? t))))


(deftest run!!-records-error-when-body-throws
  (let [err      (ex-info "boom" {:x 1})
        t        (test-task :install
                            (fn [_]
                              (throw err)))
        timeline (task/run!! t)]

    (is (= [:started :error]
           (event-types timeline)))

    (is (= err
           (:error (:payload (last timeline)))))

    (is (= "task failed"
           (:message (:payload (last timeline)))))

    (is (task/finished? t))))


(deftest run!!-rejects-terminal-task-without-running-body
  (let [called? (atom false)
        t       (test-task :install
                           (fn [_]
                             (reset! called? true)
                             :should-not-run))]

    (task/done! t {:ok? true})

    (let [err (try
                (task/run!! t)
                nil
                (catch clojure.lang.ExceptionInfo e
                  e))]

      (is (= :error/task-running
             (:type (ex-data err)))))

    (is (false? @called?))
    (is (= [:done]
           (event-types (task-events t))))))


(deftest run!-runs-async-and-returns-task
  (let [t      (test-task :install
                          (fn [_]
                            (Thread/sleep 20)
                            {:installed? true}))
        result (task/run! t)]

    (is (= t result))
    (is (wait-finished t))
    (is (= [:started :done]
           (event-types (task-events t))))))


(deftest join!!-imports-finished-child-events
  (let [parent (test-task :install)
        child  (test-task :write-root)]

    (task/progress! child 0 "writing root")
    (task/progress! child 50 "halfway")
    (task/done! child {:written? true})

    (start-task! parent)
    (task/join!! parent child)

    (let [child-events (events-for-id parent (:id child))]

      (is (= [:progress :progress :done]
             (event-types child-events)))

      (is (= [[:install :write-root]
              [:install :write-root]
              [:install :write-root]]
             (->> child-events
                  (map :path)
                  (into []))))

      ;; Child :done does not finish the parent.
      (is (false? (task/finished? parent))))))


(deftest join!!-rejects-unstarted-parent-without-importing-child-state
  (let [parent (test-task :install)
        child  (test-task :write-root)]

    (task/done! child {:written? true})

    (let [err (try
                (task/join!! parent child)
                nil
                (catch clojure.lang.ExceptionInfo e
                  e))]

      (is (= :error/task-not-running
             (:type (ex-data err))))

      (is (= {:finished? false
              :state :new
              :progress 0}
             {:finished? (task/finished? parent)
              :state (timeline/timeline->state (task/task->timeline parent))
              :progress (timeline/timeline->progress (task/task->timeline parent))})))))


(deftest join!!-joins-running-child
  (let [parent (test-task :install)
        child  (test-task :partition)
        _      (start-task! parent)
        _      (start-task! child)
        joined (future
                 (task/join!! parent child))]

    (Thread/sleep 20)

    (task/progress! child 0 "partitioning")
    (task/progress! child 100 "partitioned")
    (task/done! child {:partitioned? true})

    (is (nil? (deref joined 1000 ::timeout)))

    (is (= [:started :progress :progress :done]
           (event-types (events-for-id parent (:id child)))))))


(deftest join!!-maps-child-progress-into-parent-progress-span
  (let [parent (test-task :install)
        child  (test-task :write-root)]

    (start-task! parent)
    (task/progress! parent 20 "before child")

    (task/progress! child 0 "child start")
    (task/progress! child 50 "child middle")
    (task/done! child {:written? true})

    (task/join!! parent child 70)

    (is (= [20 20 45 70]
           (->> (progress-events parent)
                (map #(get-in % [:payload :progress]))
                (into []))))

    (is (= ["before child" "child start" "child middle" "done"]
           (->> (progress-events parent)
                (map #(get-in % [:payload :message]))
                (into []))))))


(deftest join!!-maps-child-progress-from-zero-when-parent-has-no-progress
  (let [parent (test-task :install)
        child  (test-task :unpack-pack)]

    (start-task! parent)
    (task/progress! child 50 "half unpacked")
    (task/done! child {:unpacked? true})

    (task/join!! parent child 80)

    (is (= [40 80]
           (->> (progress-events parent)
                (map #(get-in % [:payload :progress]))
                (into []))))))


(deftest join!!-propagates-child-error-to-parent
  (let [continued?* (atom false)
        child       (test-task :write-root)
        parent      (test-task :install
                               (fn [parent-task]
                                 (task/join!! parent-task child)
                                 (reset! continued?* true)))]

    (task/error! child (ex-info "child boom" {}) "write-root failed")

    (task/run!! parent)

    (is (task/finished? parent))

    (is (false? @continued?*))

    (is (= [:started :error]
           (event-types (task-events parent))))

    (let [parent-error (:error (:payload (last (task-events parent))))]
      (is (= "Child task failed"
             (ex-message parent-error)))

      (is (= :error/child-error
             (:type (ex-data parent-error))))

      (is (= "write-root failed"
             (get-in (ex-data parent-error) [:child-error :message]))))))


(deftest duplicate-join-does-not-duplicate-child-events
  (let [parent (test-task :install)
        child  (test-task :write-root)]

    (task/progress! child 100 "written")
    (task/done! child {:written? true})

    (start-task! parent)
    (task/join!! parent child)

    (let [before (task/task->timeline parent)]
      (task/join!! parent child)

      (is (= before (task/task->timeline parent)))
      (is (= [:progress :done]
             (event-types (events-for-id parent (:id child))))))))


(deftest join!!-rejects-terminal-parent-without-importing-child-events
  (let [parent (test-task :install)
        child  (test-task :write-root)]

    (start-task! parent)
    (task/done! parent {:installed? true})

    (task/progress! child 100 "written")
    (task/done! child {:written? true})

    (let [err (try
                (task/join!! parent child)
                nil
                (catch clojure.lang.ExceptionInfo e
                  e))]

      (is (= :error/task-not-running
             (:type (ex-data err)))))

    (is (= [:started :done]
           (event-types (task-events parent))))

    (is (= 0
           (count (events-for-id parent (:id child)))))))


(deftest parent-can-finish-after-successful-join
  (let [parent (test-task :install)
        child  (test-task :prepare-device)]

    (start-task! parent)
    (task/progress! parent 10 "starting")

    (task/progress! child 100 "prepared")
    (task/done! child {:prepared? true})

    (task/join!! parent child)

    (is (false? (task/finished? parent)))

    (task/done! parent {:installed? true})

    (is (task/finished? parent))

    (is (= [:started :progress :done]
           (event-types (task-events parent))))))
