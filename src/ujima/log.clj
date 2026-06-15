(ns ujima.log
  (:require [taoensso.timbre :as timbre]))


(defmacro info
  ([message]
   `(timbre/info ~message))
  ([message data]
   `(timbre/info ~message ~data)))


(defmacro warn
  ([message]
   `(timbre/warn ~message))
  ([message data]
   `(timbre/warn ~message ~data)))


(defmacro error
  ([message]
   `(timbre/error ~message))
  ([message data]
   `(timbre/error ~message ~data)))


(defmacro debug
  ([message]
   `(timbre/debug ~message))
  ([message data]
   `(timbre/debug ~message ~data)))


(defn init! [{level :level}]

  (timbre/merge-config!
    {:output-fn
     (fn [{:keys [level ?ns-str msg_]}]
       (str "[" (name level) "] "
            "[" ?ns-str "] "
            (force msg_)))})

  (timbre/set-min-level! level))

