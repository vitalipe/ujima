(ns tools.circle.console
  "The console as a process the CLI starts and stops. It records the pid it spawned the way
   the sim records the addresses it claimed, so `down!` stops that process and not whatever
   else happens to mention the console on its command line."
  (:require [babashka.process   :as p]
            [tools.circle.sim   :as sim]
            [tools.circle.state :as state]))


(def ^:private state-file (state/file "ujima-circle-console.edn"))
(def ^:private stop-timeout-ms 5000)


(defn running
  "The pid of the console this host is running, nil when none is."
  []
  (when-let [claim (state/read! state-file)]
    (when (state/ours? claim) (:pid claim))))


(defn up!
  "The console in dev. The device hands it these two in the environment; here the CLI does."
  [{:keys [self token]}]
  (let [self (or self
                 (when-let [fake (first (sim/claimed))]
                   (println (str "self: " fake " — the sim's first machine"))
                   fake))]
    (when-not self
      (throw (ex-info "no <self-ip>, and no sim running to borrow one from" {})))
    (when-let [pid (running)]
      (throw (ex-info (str "a console is already running (pid " pid ") — `bb circle console down`")
                      {:pid pid})))

    (let [proc (p/process {:dir "desktop/console"
                           :out :inherit :err :inherit
                           :extra-env {"UJIMA_SELF"          self
                                       "UJIMA_CIRCLE_TOKEN" (or token sim/default-token)}}
                          "bb" "--config" (str (System/getProperty "user.dir") "/bb.edn")
                          "-m" "console.main")
          ph   (.toHandle (:proc proc))]
      (state/write! state-file {:pid (.pid ph) :started (state/started-at ph)})
      ;; leaving takes the child and the record with it, however we leave
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. #(do (.destroy (:proc proc))
                                      (state/clear! state-file))))
      @proc
      (state/clear! state-file))))


(defn down!
  [_]
  (if-let [claim (state/read! state-file)]
    (if-not (state/ours? claim)
      (do (println "clearing a stale record from pid" (:pid claim))
          (state/clear! state-file))
      (do (println "stopping pid" (:pid claim) "...")
          (state/stop! claim stop-timeout-ms)
          (if (state/ours? claim)
            (println (str "pid " (:pid claim) " will not stop"))
            (do (state/clear! state-file)
                (println "console stopped")))))
    (println "no console running")))
