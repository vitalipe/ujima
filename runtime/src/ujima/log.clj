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


;; <N> syslog-priority prefix: journald strips it and sets PRIORITY, so `journalctl -p` filters by level.
(def ^:private level->syslog {:trace 7 :debug 7 :info 6 :warn 4 :error 3 :fatal 2 :report 5 :spy 7})


(defn init! [{level :level :or {level :info}}]

  (timbre/merge-config!
    {:output-fn
     (fn [{:keys [level ?ns-str msg_]}]
       (str "<" (level->syslog level 6) ">"
            "[" (name level) "] "
            "[" ?ns-str "] "
            (force msg_)))})

  (timbre/set-min-level! level))

