(ns lib.throttle
  (:import
   [java.util.concurrent Executors TimeUnit ThreadFactory]))


(defonce ^:private throttle-scheduler
  (Executors/newSingleThreadScheduledExecutor
   (reify ThreadFactory
     (newThread [_ r]
       (doto (Thread. r "ujima-throttle-scheduler")
         (.setDaemon true))))))


(defn- now-ns []
  (System/nanoTime))


(defn- ms->ns [ms]
  (.toNanos TimeUnit/MILLISECONDS (long ms)))


(defn- schedule-once! [delay-ns f]
  (.schedule throttle-scheduler
             ^Runnable
             (reify Runnable
               (run [_]
                 (f)))
             (long (max 0 delay-ns))
             TimeUnit/NANOSECONDS))


(defn- deliver-call!
  [promises f args]
  (try
    (let [result (apply f args)]
      (doseq [p promises]
        (deliver p {:ok? true
                    :value result})))
    (catch Throwable e
      (doseq [p promises]
        (deliver p {:ok? false
                    :error e})))))


(defn throttle-leading-trailing
  "Rate-limit f to one run per interval: an idle throttle runs the next call
  right away; calls landing inside the window coalesce (newest args win) into
  one trailing run, so the final call's value always lands. Every call returns
  a promise of its run's outcome — {:ok? true :value v} | {:ok? false :error e};
  coalesced calls share a run.

  Every run executes on the shared scheduler thread, so runs of f NEVER overlap
  — an f slower than the interval pushes back its own next run (and any other
  throttle's timers) instead of racing itself."
  [interval-ms f]
  (let [interval-ns (ms->ns interval-ms)
        lock   (Object.)
        state* (atom {:last-run-ns nil
                      :scheduled? false
                      :pending-args nil
                      :pending-promises []})]

    (letfn [(delay-to-next-slot-ns []
              ;; counted from the START of the previous run; schedule-once! clamps to 0
              (if-let [last (:last-run-ns @state*)]
                (- interval-ns (- (now-ns) last))
                0))

            (run-pending! []
              (let [{:keys [args promises]}
                    (locking lock
                      (let [s @state*]
                        ;; :scheduled? stays true while f runs — callers keep coalescing
                        (swap! state* assoc
                               :last-run-ns (now-ns)
                               :pending-args nil
                               :pending-promises [])
                        {:args (:pending-args s) :promises (:pending-promises s)}))]
                (deliver-call! promises f args)
                (locking lock
                  (if (:pending-args @state*)   ; arrived while f ran -> next slot
                    (schedule-once! (delay-to-next-slot-ns) run-pending!)
                    (swap! state* assoc :scheduled? false)))))]

      (fn [& args]
        (let [p (promise)]
          (locking lock
            (swap! state* #(-> %
                               (assoc :pending-args args)
                               (update :pending-promises conj p)))
            (when-not (:scheduled? @state*)
              (swap! state* assoc :scheduled? true)
              (schedule-once! (delay-to-next-slot-ns) run-pending!)))
          p)))))