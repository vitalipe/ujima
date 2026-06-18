(ns lib.task
  "Observable, single-start tasks for process-like operations.

   A task is created cold with its code function and is started once by `run!`
   or `run!!`. Its append-only timeline is the source of truth for state and
   history; its core.async channel provides live notifications for consumers
   that also read or poll that timeline. A running parent can import child task
   events with `join!!`."

  (:require [clojure.core.async :as async]
            [lib.task.timeline :refer [->TimelineEvent 
                                         terminal-event? 
                                         timeline->last-of 
                                         timeline->last-of-type
                                         timeline->state 
                                         timeline->progress]]))


(def next-id* (atom 0))


(defn- clamp [from to value]
  (-> value
      (max from)
      (min to)))


(defn- round [value]
  (-> value
    (double)
    (Math/round)))


(defn- commit-event! 

  ([timeline* id event] 
   (commit-event! timeline* id event (constantly true)))
  
  ([timeline* id event allow?]  
   (loop []
    
     (let [prv    @timeline*
           nxt    (conj prv event)
           cas!   (partial compare-and-set! timeline*) 
          
           ?parent-ended (terminal-event? (timeline->last-of prv id))  
           ?event-ended  (terminal-event? (timeline->last-of prv (:id event)))
           ?allowed      (allow? prv)]
                             
       (cond 

         ?parent-ended  nil
         ?event-ended   nil
         (not ?allowed) nil


         ;; try cmp-set!
         (cas! prv nxt)  event
        
         ;; retry
         :otherwise      (recur))))))


(defn- task-event [{:keys [id name]} type payload]
  (->TimelineEvent id [name] type (java.time.Instant/now) payload))


(defrecord UjimaTask [id          ;; task id, use this to filter 
                      name        ;; task name keyword, used to group child tasks etc
                      ch*         ;; carries wake-up/event notifications, must use a sliding buffer so `event!` 
                                  ;; does not block on slow consumers.

                      timeline*   ;; task timeline append only, the source of truth.
                      code])


(defn task?
  "Returns true when `x` is a task (a UjimaTask record)."
  [x]
  (instance? UjimaTask x))


(defn task->timeline
  "Returns a snapshot of the task's append-only event timeline.

   This is the source of truth for polling clients and derived task state. A
   parent task's timeline may include imported child events."
  
  [{timeline :timeline*}] 
  @timeline)


(defn task->state
  "Returns the root task state derived from its current timeline."
  [{timeline* :timeline*}] 
  (timeline->state @timeline*))


(defn finished?
  "Returns true when the root task has recorded a terminal event."
  [{id :id timeline* :timeline*}]
  (->  @timeline* 
    (timeline->last-of id)
    (terminal-event?)))


(defn event! 
  "Conditionally appends an event and publishes it to the task's channel.

   With two arguments, appends when the root task is not terminal. 

   With `allow?`, the predicate is checked against the current
   timeline as part of the same compare-and-set retry loop used to append the
   event.

   Returns the committed event, or nil when the event was rejected."
  ([task event] (event! task event (constantly true)))
  ([{:keys [ch* id timeline*]} event allow?]
   (when-let [event (commit-event! timeline* id event allow?)]
     (async/>!! ch* event)
     event)))


(defn progress!
  "Records a progress event for a non-terminal task.

   Progress is rounded and clamped to the range 0 through 100. `message` is
   optional human-readable status text."

  ([task progress]
   (progress! task progress nil))

  ([task progress message]
   (event! task (task-event task :progress {:message message
                                            :progress (->> progress 
                                                        (clamp 0 100) 
                                                        (round))})))) 


(defn done!
  "Records successful completion with `value` and closes live notifications."
  [task value]  
  (let [event (event! task (task-event task :done value))]
    (async/close! (:ch* task))
    event))


(defn error! 
  "Records failed completion with a Throwable and closes live notifications.

   The event payload stores the error and a human-readable message."
  ([task error] (error! task error "task failed"))
  ([task error message]
   (let [event (event! task (task-event task :error {:error error :message message}))]
     (async/close! (:ch* task))
     event)))


 (defn run!!
  "Claims and runs a cold task on the current thread.

   Claims `:started` atomically. If the stored code returns normally, records
   `:done`; if it throws, records `:error`. Throws `:error/task-running` when
   another runner has already claimed or completed this task.

   Returns the task timeline after execution finishes."

  [{f :code id :id name :name :as task}]
  (if (event! task
              (task-event task :started {})
              #(nil? (timeline->last-of % id)))
    (do
      (try
        (done! task (f task))
        (catch Throwable err
          (error! task err)))
        
      (task->timeline task))

    ;; nil on (event! ...)
    (throw
      (ex-info "Cannot run task that is already started"
                {:type :error/task-running
                 :task name}))))


(defn run!
  "Claims a cold task and runs its stored code on an async thread.

   The `:started` claim occurs before this function returns, so competing starts
   fail immediately with `:error/task-running`. Code completion or failure is
   recorded as `:done` or `:error`.

   Returns the started task immediately."
  [{f :code id :id name :name :as task}]
  (if (event! task
              (task-event task :started {})
              #(nil? (timeline->last-of % id)))
    
    (async/thread
      (try
        (done! task (f task))
        (catch Throwable err
          (error! task err))))
        
    ;; nil on (event! ...)
    (throw
      (ex-info "Cannot run task that is already started"
                {:type :error/task-running
                 :task name})))
  task)



(defn take!!
  "Blocks for the next live task notification, or nil after channel closure."
  [task]
  (async/<!! (:ch* task)))
 

(defn join!! 
  "Joins a child task into a running parent and blocks until the child ends.

   The parent must already be running. If `child` is cold, this function starts
   it; otherwise it consumes its existing or live events. Child events are
   imported into the parent timeline and published as parent notifications.
   When `target-%` is supplied, child progress is mapped into the parent's
   remaining progress span.

   Throws when the parent is not running or when the child fails, allowing the
   parent's runner to record its own failure and stop dependent work."
  ([task child]  
   (join!! task child nil))
   
  ([{task-name :name :as task} child target-%]
   (let [start-% (timeline->progress (task->timeline task))
         scale   (fn [p-%] 
                    (+ start-% 
                       (* (- target-% start-%) 
                          (/ p-% 100))))

         dispatch! (fn [{:keys [type path], {c% :progress, msg :message} :payload, :as evt}]
                     (let [evt-with-merged-path (assoc evt :path (into [task-name] path))]
                       
                       ;; merge child event
                       (event! task evt-with-merged-path) 

                       ;; propagate child errors
                       (when (= type :error)
                         (throw
                           (ex-info "Child task failed"
                                    {:type        :error/child-error
                                     :child-error (:payload evt)})))

                       ;; progress 
                       (when target-% 
                         (when (= type :progress)
                           (progress! task (scale c%) msg))  

                         (when (= type :done)
                           (progress! task target-% "done")))))]     


     (when-not (= :running (task->state task))
       (throw 
         (ex-info "Cannot join child into a task that is not running"
                  {:type :error/task-not-running :task task-name})))

     (when (= :new (task->state child))
       (run! child))     

     (loop [taken 0]
       (let [timeline (task->timeline child)
             [nxt]    (drop taken timeline)
             done     (->  timeline 
                        (timeline->last-of (:id child))
                        (terminal-event?))]

         (cond 
           nxt        (do ;; dispatch! and take next 
                        (dispatch! nxt)
                        (recur (inc taken)))
            
           (not done) (do  ;; wait for more events
                        (when (take!! child) ;; just in case to prevent infi loop, if ch closed without teminal event 
                          (recur taken)))

           :otherwise  nil))))))


(defn ->task 
 "Creates a cold task named `name` with stored execution function `f`.

   `name` should be a keyword, for example:

   - `:install`
   - `:partition`
   - `:write-root`

   Construction does not execute `f` or add lifecycle events. Start the task
   once with `run!` or `run!!`, or pass it as a cold child to `join!!`."
  [name f]
  (->UjimaTask (swap! next-id* inc)
               name
               (async/chan (async/sliding-buffer 128))
               (atom [])
               f))
