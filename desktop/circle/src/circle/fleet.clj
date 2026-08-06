(ns circle.fleet
  "Jobs over a fleet transport. One act = one lib.task: per-target replies
   append :peer-result events as they arrive, and the reply deadline appends
   :noreply for the silent ones. A job view is a reduce of that timeline —
   per-verb meaning (restart's :ok reads as :accepted) lives only here."
  (:require [lib.task          :as task]
            [lib.task.timeline :refer [->TimelineEvent]]))


(def ^:private reply-timeout-ms 5000)
(def ^:private keep-jobs        32)

(defonce ^:private jobs* (atom {}))   ;; job id -> {:task :verb :targets}


(defn- result-event [{:keys [id name]} peer result]
  (->TimelineEvent id [name] :peer-result (java.time.Instant/now) {:peer peer :result result}))

(defn- unanswered? [timeline peer]
  (not-any? #(and (= :peer-result (:type %))
                  (= peer (get-in % [:payload :peer])))
            timeline))

(defn- fanout [send! verb targets args]
  (fn [t]
    (let [reply!   (fn [peer result]
                     (task/event! t (result-event t peer result) #(unanswered? % peer)))
          futs     (doall (for [peer targets]
                            (future
                              (let [reply (send! peer verb args)]
                                (when (not= :noreply reply)
                                  (reply! peer reply))))))
          deadline (+ (System/currentTimeMillis) reply-timeout-ms)]
      (doseq [f futs]
        (deref f (max 1 (- deadline (System/currentTimeMillis))) :timeout))
      (doseq [peer targets]
        (reply! peer :noreply))
      nil)))


(defn act!
  "Starts verb against targets on an async thread; returns the job id."
  [{send! :send!} verb targets args]
  (let [t (task/->task verb (fanout send! verb targets args))]
    (swap! jobs* (fn [jobs]
                   (->> (assoc jobs (:id t) {:task t :verb verb :targets targets})
                        (sort-by key)
                        (take-last keep-jobs)
                        (into {}))))
    (task/run! t)
    (:id t)))


(defn- shown-status [verb result]
  (if (and (#{:restart :poweroff} verb) (= :ok result)) :accepted result))

(defn- job-view [{:keys [task verb targets]}]
  (let [results (into {}
                      (comp (filter #(= :peer-result (:type %)))
                            (map (fn [{{:keys [peer result]} :payload}]
                                   [peer (shown-status verb result)])))
                      (task/task->timeline task))]
    {:job      (:id task)
     :verb     verb
     :finished (boolean (task/finished? task))
     :peers    (into {} (map (fn [id] [id (get results id :pending)])) targets)}))

(defn job [id]
  (some-> (get @jobs* id) job-view))

(defn jobs []
  (->> @jobs* (sort-by key) vals (mapv job-view)))

(defn active-action
  "The panel's single running action: the latest unfinished job, nil when idle."
  []
  (some-> (->> @jobs*
               (sort-by key)
               vals
               (remove #(task/finished? (:task %)))
               last)
          job-view))
