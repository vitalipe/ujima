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


(defn- commit-event! [timeline* id event]
  (loop []
    
    (let [prv    @timeline*
          nxt    (conj prv event)
          cas!   (partial compare-and-set! timeline*) 
          
          p-ended (terminal-event? (timeline->last-of prv id))  
          e-ended (terminal-event? (timeline->last-of prv (:id event)))]
                            
      (cond 

        ;; parent task is terminal:
        p-ended nil

        ;; prevents duplicate child joins / late child events.
        e-ended  nil

        ;; try cmp-set!
        (cas! prv nxt)  event
        
        ;; retry
        :otherwise      (recur)))))


(defn- task-event [{:keys [id name]} type payload]
  (->TimelineEvent id [name] type (java.time.Instant/now) payload))


(defrecord UjimaTask [id          ;; task id, use this to filter 
                      name        ;; task name keyword, used to group child tasks etc
                      
                      ch*         ;; carries wake-up/event notifications, must use a sliding buffer so `event!` 
                                  ;; does not block on slow consumers.
                      
                      timeline*]) ;; task timeline append only, the source of truth.


(defn task->timeline 
  "Returns the full task timeline.

   The timeline is an immutable snapshot of the task's append-only event log at
   the time this function is called. It may include imported child events."
  
  [{timeline :timeline*}] 
  @timeline)



(defn finished? [{id :id timeline* :timeline*}]
  (->  @timeline* 
    (timeline->last-of id)
    (terminal-event?)))


(defn event! 
  "Appends an event to task's timeline and publishes it to task's channel.

   This is the basic event-writing primitive. It mutates `timeline*` and then
   writes the event to `ch*`.

   Returns the event or nil."

  [{:keys [ch* id timeline*]} event]

  (when-let [event (commit-event! timeline* id event)]
    (async/>!! ch* event)
    event))


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
  "Runs f against task on the current thread.

   Emits :started first. If f returns normally, emits :done. If f throws, emits
   :error.

   Returns the task timeline after execution finishes.

   Blocks until f finishes."
  [task f]
  (when (event! task (task-event task :started {}))
    (try
      (let [value (f task)]
        (done! task value))

      (catch Throwable err
        (error! task err))))

  (task->timeline task))


(defn run!
  "Runs f in an async thread and returns task immediately."
  [task f]
  (async/thread
    (run!! task f))
  task)


(defn take!! [task]
  (async/<!! (:ch* task)))
 

(defn join!! 
  "Takes ownership of child, imports all child timeline events into task, 
   forwards imported events to task’s live channel, 
   and blocks until `(finished? child)`."
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
                         (error! task :error/child-error "child task error"))

                       ;; progress 
                       (when target-% 
                         (when (= type :progress)
                           (progress! task (scale c%) msg))  

                         (when (= type :done)
                           (progress! task target-% "done")))))]     

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
  [name]
  (->UjimaTask (swap! next-id* inc)
               name
               (async/chan (async/sliding-buffer 128))
               (atom [])))
