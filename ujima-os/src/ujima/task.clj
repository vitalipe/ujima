(ns ujima.task
  "Small task abstraction for long-running operations.

   A task is an observable unit of work. It owns an append-only timeline of
   structured events and a core.async channel used for live wake-up/event
   notifications."

  (:require [clojure.core.async :as async]
            [ujima.task.timeline :refer [->TimelineEvent 
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


(defn task->timeline 
  "Returns the full task timeline.

   The timeline is an immutable snapshot of the task's append-only event log at
   the time this function is called. It may include imported child events."
  
  [{timeline :timeline*}] 
  @timeline)


(defn task->state [{timeline* :timeline*}] 
  (timeline->state @timeline*))


(defn finished? [{id :id timeline* :timeline*}]
  (->  @timeline* 
    (timeline->last-of id)
    (terminal-event?)))


(defn event! 
  "Appends an event to task's timeline and publishes it to task's channel.

   This is the basic event-writing primitive. It mutates `timeline*` and then
   writes the event to `ch*`.

   Returns the event or nil."
  ([task event] (event! task event (constantly true)))
  ([{:keys [ch* id timeline*]} event allow?]
   (when-let [event (commit-event! timeline* id event allow?)]
     (async/>!! ch* event)
     event)))


(defn progress!
  "Records task progress.

   progress should be a number from 0 to 100.
   message is optional human-readable status text."

  ([task progress]
   (progress! task progress nil))

  ([task progress message]
   (event! task (task-event task :progress {:message message
                                            :progress (->> progress 
                                                        (clamp 0 100) 
                                                        (round))})))) 


(defn done! [task value]  
  (let [event (event! task (task-event task :done value))]
    (async/close! (:ch* task))
    event))


(defn error! 
  ([task error] (error! task error "task failed"))
  ([task error message]
   (let [event (event! task (task-event task :error {:error error :message message}))]
     (async/close! (:ch* task))
     event)))


 (defn run!!
  "Runs the task on the current thread (blocks).

   Emits :started first. If f returns normally, emits :done. If f throws, emits
   :error.

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
  "Runs the task an async thread and returns task immediately."
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



(defn take!! [task]
  (async/<!! (:ch* task)))
 

(defn join!! 
  "Takes ownership of child, imports all child timeline events into task, 
   forwards imported events to task’s live channel, 
   and blocks until `(finished? child)`.

   Throws when child fails so the parent runner can record its own failure and
   stop dependent work."
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
 "Creates a new task with name.

   name should be a keyword, for example:

   - `:install`
   - `:partition`
   - `:write-root`

   Lifecycle events are added by `run!`, `event!`, `done!`, `error!`."
  [name f]
  (->UjimaTask (swap! next-id* inc)
               name
               (async/chan (async/sliding-buffer 128))
               (atom [])
               f))
