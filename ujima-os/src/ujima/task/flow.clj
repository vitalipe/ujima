(ns ujima.task.flow
  (:require [clojure.core.async :as async]
            [ujima.task :as task]))


(defn flow-error-ex [type message]
  (ex-info message {:type type}))


(defmacro flow!
  "Creates and starts a root task flow.

   Inside the body, these lexical helpers are available:

   - task*
   - progress!
   - error!

   Nested child tasks should be created with <step!.
   Existing external tasks should be joined with <join!."
  [name & body]
  `(let [task# (task/->task ~name)]
     (task/run!
       task#
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

           ~@body)))

     task#))


(defmacro <join!
  "Joins an existing child task into the current flow.

   target-progress is the parent progress value the child should map to when it
   completes successfully.

   Must be used inside flow!."
  [target-progress child-expr]
  `(let [child# ~child-expr]
     (task/join!! ~'task* child# ~target-progress)
     child#))


(defmacro <step!
  "Creates a child flow and joins it into the current flow.

   target-progress is the parent progress value this step should reach when it
   completes successfully.

   Must be used inside flow!."
  [target-progress name & body]
  `(let [child# (flow! ~name
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
