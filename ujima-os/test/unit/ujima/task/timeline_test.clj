(ns ujima.task.timeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [ujima.task.timeline :as timeline]))


(deftest terminal-event?-detects-terminal-events
  (testing "terminal events"
    (is (timeline/terminal-event?
          (timeline/->TimelineEvent 1 [:install] :done nil {})))

    (is (timeline/terminal-event?
          (timeline/->TimelineEvent 1 [:install] :error nil {})))

    (is (timeline/terminal-event?
          (timeline/->TimelineEvent 1 [:install] :cancelled nil {}))))

  (testing "non-terminal events"
    (is (not
          (timeline/terminal-event?
            (timeline/->TimelineEvent 1 [:install] :started nil {}))))

    (is (not
          (timeline/terminal-event?
            (timeline/->TimelineEvent 1 [:install] :progress nil {:progress 10}))))

    (is (not
          (timeline/terminal-event? nil)))))


(deftest timeline->last-of-returns-latest-event-for-id
  (let [timeline [(timeline/->TimelineEvent 1 [:install] :started nil {})
                  (timeline/->TimelineEvent 2 [:install :unpack] :started nil {})
                  (timeline/->TimelineEvent 1 [:install] :progress nil {:progress 10})
                  (timeline/->TimelineEvent 2 [:install :unpack] :done nil {})
                  (timeline/->TimelineEvent 1 [:install] :progress nil {:progress 50})]]

    (is (= :progress
           (:type (timeline/timeline->last-of timeline 1))))

    (is (= {:progress 50}
           (:payload (timeline/timeline->last-of timeline 1))))

    (is (= :done
           (:type (timeline/timeline->last-of timeline 2))))

    (is (nil? (timeline/timeline->last-of timeline 999)))

    (is (nil? (timeline/timeline->last-of timeline nil)))))


(deftest timeline->last-of-type-returns-latest-event-for-id-and-type
  (let [timeline [(timeline/->TimelineEvent 1 [:install] :started nil {})
                  (timeline/->TimelineEvent 1 [:install] :progress nil {:progress 10})
                  (timeline/->TimelineEvent 2 [:install :write-root] :progress nil {:progress 25})
                  (timeline/->TimelineEvent 1 [:install] :progress nil {:progress 60})
                  (timeline/->TimelineEvent 1 [:install] :done nil {:ok? true})]]

    (is (= {:progress 60}
           (:payload (timeline/timeline->last-of-type timeline 1 :progress))))

    (is (= {:progress 25}
           (:payload (timeline/timeline->last-of-type timeline 2 :progress))))

    (is (= {:ok? true}
           (:payload (timeline/timeline->last-of-type timeline 1 :done))))

    (is (nil? (timeline/timeline->last-of-type timeline 1 :error)))
    (is (nil? (timeline/timeline->last-of-type timeline 999 :progress)))))


(deftest timeline->state-returns-new-for-empty-timeline
  (is (= :new
         (timeline/timeline->state []))))


(deftest timeline->state-uses-first-event-id-as-root-task-id
  (let [timeline [(timeline/->TimelineEvent 1 [:install] :started nil {})
                  (timeline/->TimelineEvent 2 [:install :unpack] :started nil {})
                  (timeline/->TimelineEvent 2 [:install :unpack] :done nil {})
                  (timeline/->TimelineEvent 1 [:install] :progress nil {:progress 50})]]

    (is (= :running
           (timeline/timeline->state timeline)))))


(deftest timeline->state-returns-terminal-state-for-root-task
  (testing ":done"
    (let [timeline [(timeline/->TimelineEvent 1 [:install] :started nil {})
                    (timeline/->TimelineEvent 2 [:install :unpack] :done nil {})
                    (timeline/->TimelineEvent 1 [:install] :done nil {:ok? true})]]

      (is (= :done
             (timeline/timeline->state timeline)))))

  (testing ":error"
    (let [timeline [(timeline/->TimelineEvent 1 [:install] :started nil {})
                    (timeline/->TimelineEvent 1 [:install] :error nil {:message "boom"})]]

      (is (= :error
             (timeline/timeline->state timeline)))))

  (testing ":cancelled"
    (let [timeline [(timeline/->TimelineEvent 1 [:install] :started nil {})
                    (timeline/->TimelineEvent 1 [:install] :cancelled nil {:reason :user})]]

      (is (= :cancelled
             (timeline/timeline->state timeline))))))


(deftest timeline->progress-returns-zero-for-empty-timeline
  (is (= 0
         (timeline/timeline->progress []))))


(deftest timeline->progress-returns-hundred-for-done-root-task
  (let [timeline [(timeline/->TimelineEvent 1 [:install] :started nil {})
                  (timeline/->TimelineEvent 1 [:install] :progress nil {:progress 60})
                  (timeline/->TimelineEvent 1 [:install] :done nil {:ok? true})]]

    (is (= 100
           (timeline/timeline->progress timeline)))))


(deftest timeline->progress-returns-latest-progress-for-running-root-task
  (let [timeline [(timeline/->TimelineEvent 1 [:install] :started nil {})
                  (timeline/->TimelineEvent 1 [:install] :progress nil {:progress 10})
                  (timeline/->TimelineEvent 2 [:install :write-root] :progress nil {:progress 80})
                  (timeline/->TimelineEvent 1 [:install] :progress nil {:progress 55})]]

    (is (= 55
           (timeline/timeline->progress timeline)))))


(deftest timeline->progress-defaults-to-zero-when-running-task-has-no-progress
  (let [timeline [(timeline/->TimelineEvent 1 [:install] :started nil {})
                  (timeline/->TimelineEvent 2 [:install :write-root] :progress nil {:progress 80})
                  (timeline/->TimelineEvent 2 [:install :write-root] :done nil {})]]

    (is (= 0
           (timeline/timeline->progress timeline)))))


