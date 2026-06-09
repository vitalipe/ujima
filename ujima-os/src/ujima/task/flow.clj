(ns ujima.task.flow
  "Macros for constructing task programs and joining nested steps.

   `flow` creates a cold task, while `flow!` constructs and synchronously runs
   a root task. Nested steps remain cold until joined by their parent."
  (:require [ujima.task :as task]))


(defn flow-error-ex
  "Creates the structured exception thrown by a flow's `error!` helper."
  [type message]
  (ex-info message {:type type}))


(defmacro flow
  "Creates a cold task whose stored code executes `body`.

   Inside the body, these lexical helpers are available:

   - task*
   - progress!
   - error!

   Construction does not run `body`. Start a root task with `task/run!` or
   `task/run!!`; nested child tasks should be created with `<step!`, and
   existing external tasks should be joined with `<join!`."
  [name & body]
  `(task/->task
     ~name
     (fn [~'task*]
       (let [~'progress! (fn
                           ([progress#]
                            (task/progress! ~'task* progress#))
                           ([progress# message#]
                            (task/progress! ~'task* progress# message#)))

             ~'error!    (fn
                           ([type# message#]
                            (throw (flow-error-ex type# message#)))
                           ([error#]
                            (throw error#)))]

         ~@body))))


(defmacro flow!
  "Creates a root flow and runs it synchronously with `task/run!!`.

   Returns the completed root task timeline."
  [name & body]
  `(task/run!! (flow ~name ~@body)))


(defmacro <join!
  "Joins an existing child task into the current running flow.

   `target-progress` is the parent progress value the child should map to when
   it completes successfully. The child may be cold; `task/join!!` starts it
   when necessary. Returns the child task.

   Must be used inside flow or flow!."
  [target-progress child-expr]
  `(let [child# ~child-expr]
     (task/join!! ~'task* child# ~target-progress)
     child#))


(defmacro <step!
  "Creates a cold child flow and joins it into the current running flow.

   `target-progress` is the parent progress value this step should reach when
   it completes successfully. `task/join!!` owns starting the child. Returns
   the child task after the blocking join completes.

   Must be used inside flow or flow!."
  [target-progress name & body]
  `(let [child# (flow ~name
                  ~@body)]
     (task/join!! ~'task* child# ~target-progress)
     child#))


(comment "example"

  (flow! :install-ujima

    (sh! ..)
    (progress! 10 "disks ok")

    (<step! 40 :parition-disk
      
      (sh! ...)
      (progress! 20 "disk wiped")
      
      (sh! ..)
      (progress! 50 "MBR table created")

      (sh! ..)
      (progress! 90 "a/b patitions created")

      (when-not (:ok? (sh ..))
        (error! :error/patition-table "failed to verify pratition table")))


    (<join! 90 (pack/unpack! ..)) ;; external fn that retutns a task

    (when-not (validate! ...)
      (error! :error/bad-disk "install failed"))

    (progress! 100 "system valid")
    {:status ...}))
