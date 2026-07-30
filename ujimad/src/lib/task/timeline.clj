(ns lib.task.timeline
   "helpers for working with Ujima task timelines.

   A timeline is an append-only vector of TimelineEvent records. These helpers
   derive state, progress, and latest events from that vector without mutating
   tasks, channels, or atoms.")


(defrecord TimelineEvent [id        ;; task id
                          path      ;; vector of task names, for example [:install :partition]
                          type      ;; event type
                          time      ;; time Instant
                          payload]) ;; payload can be anything


(defn terminal-event? 
  "Returns true when event is a terminal task event.

   Terminal events mean the task has logically finished and no more appends
   should be accepted for that task id.

   Terminal event types are:

   - `:done`
   - `:error`
   - `:cancelled`"
  [event]
  (contains? #{:done :error :cancelled} (:type event)))


(defn timeline->last-of 
  "Returns the latest event in timeline for task id.
   Returns nil when id is nil or no matching event exists."
  [timeline id]
  (when id 
    (->> (rseq timeline)
      (filter #(= id (:id %)))
      (first))))


(defn timeline->last-of-type 
   "Returns the latest event in timeline for task id and event type.

   Example: `(timeline->last-of-type timeline task-id :progress)`
   Returns nil when no matching event exists."  
  [timeline id type]
  (->> (rseq timeline)
    (filter #(= id    (:id %)))
    (filter #(= type (:type %)))
    (first)))


(defn timeline->state 
  "Derives the lifecycle state of the first task id found in timeline.
   
   Returns:

    `:new`   when the timeline is empty
    `:done`, :error, or :cancelled when the latest event for the task is terminal
    `:running` otherwise

    This function uses the id of the first event in the timeline as the task id."
  [[{first-id :id} :as timeline]]
  (let [{state :type} (timeline->last-of timeline first-id)
        ?terminal     (#{:done :error :cancelled} state)]
    (cond
      (empty? timeline) :new                
      ?terminal         state 
      :otherwise        :running)))


(defn timeline->progress 
  "Derives task progress from timeline.

   `:done` is always 100.
   `:new` is always 0.

   Otherwise, uses the latest `:progress` event, defaulting to 0."
  [[{id :id} :as timeline]] 
  (let [state (timeline->state timeline)]
    (case state
      :done 100
      :new  0      
      
      ;; based on last progress event
      (-> timeline
        (timeline->last-of-type id :progress) 
        (get-in [:payload :progress] 0)))))
    
