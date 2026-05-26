(ns ujima.task.flow
  (:require [ujima.task :as task]))


(defn flow-error-ex [type message]
  (ex-info message {:type type}))


(defmacro flow
  "Creates a cold task flow.

   Inside the body, these lexical helpers are available:

   - task*
   - progress!
   - error!

   Nested child tasks should be created with <step!.
   Existing external tasks should be joined with <join!."
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
  "Creates and synchronously runs a root task flow.

   Returns the value returned by `task/run!!`."
  [name & body]
  `(task/run!! (flow ~name ~@body)))


(defmacro <join!
  "Joins an existing child task into the current flow.

   target-progress is the parent progress value the child should map to when it
   completes successfully.

   Must be used inside flow or flow!."
  [target-progress child-expr]
  `(let [child# ~child-expr]
     (task/join!! ~'task* child# ~target-progress)
     child#))


(defmacro <step!
  "Creates a cold child flow and joins it into the current flow.

   target-progress is the parent progress value this step should reach when it
   completes successfully. `task/join!!` owns starting the child.

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
