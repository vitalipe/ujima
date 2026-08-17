(ns ujima.linux.systemd
  "Per-app systemd --user scopes: the process-liveness + kill handle. The i3 tree owns identity
   and display; a scope only answers 'is this app's family alive?' and 'kill it.' Unit names are
   launch-unique (<id>-<millis>) so a relaunch never collides with a still-deactivating unit."
  (:require [clojure.string :as str]
            [lib.shell :as shell]))


(def ^:private prefix "ujima-app-")

(defn- glob [id] (str prefix (name id) "-*.scope"))


(defn id-of-unit
  "The app-id in a scope unit name (ujima-app-<id>-<millis>.scope); nil if not ours."
  [s]
  (some->> s (re-find (re-pattern (str prefix "(.+)-\\d+\\.scope"))) second keyword))


(defn spawn-scoped!
  "Launch EXEC (argv vector) into a fresh scope for ID; DIR is the process cwd, nil inherits.
   --no-new-privs: nothing in the scope, or anything it spawns, can regain privilege through
   setuid — so a spawned shell cannot sudo.

   --scope blocks, so never deref this. And the flag is set by setpriv in the forked process
   because a scope has no NoNewPrivileges= property — systemd accepts one and ignores it.

   --expand-environment=no keeps EXEC literal: app.edn is external data, and systemd warns it
   will start expanding $VAR in scope command lines in a future release.

   OPTS are spawn options, merged over the defaults:

     (spawn-scoped! :console [\"ujima-console\"] \"/ujima/apps/console\"
                    {:extra-env {\"UJIMA_CIRCLE_TOKEN\" token}})

   Keep secrets out of EXEC: systemd-run copies the command line into the scope's
   Description, and systemd logs that to the journal."
  [id exec dir opts]
  (apply shell/sh (merge {:out :inherit :err :inherit :dir dir} opts)
         :systemd-run :--user :--scope :--collect
         (str "--unit=" prefix (name id) "-" (System/currentTimeMillis))
         "--property=TimeoutStopSec=3"
         "--expand-environment=no"
         "--" :setpriv :--no-new-privs exec))


(defn- live-units [pattern]
  (let [{:keys [ok? out]} (shell/sh? :systemctl :--user "list-units" "--plain" "--no-legend"
                                     "--state=active" pattern)]
    (when ok?
      (keep #(some-> (re-find #"^(ujima-app-\S+\.scope)" (str/trim %)) second)
            (str/split-lines (or out ""))))))


(defn active?
  "Sync: does ID have a live scope? The launch gate + the open-url cold/warm test."
  [id]
  (boolean (seq (live-units (glob id)))))


(defn stop!
  "Force-kill ID's scope — reaps the whole cgroup. Missing is fine."
  [id]
  (doseq [u (live-units (glob id))] (shell/sh? :systemctl :--user "stop" u)))


(defn- live-app-ids [] (into #{} (keep id-of-unit) (live-units (str prefix "*.scope"))))


(defn watch-scopes!
  "Poll live scopes every interval; call (EMIT {:type :scope/died :app-id X}) for any app whose
   scope disappeared. Pure event source — the crash/self-quit go-home backstop; holds only its
   own previous snapshot."
  [{:keys [interval-ms emit] :or {interval-ms 1000}}]
  (future
    (loop [prev #{}]
      (let [cur (try (live-app-ids) (catch Throwable _ prev))]
        (doseq [id prev :when (not (contains? cur id))]
          (emit {:type :scope/died :app-id id}))
        (Thread/sleep (long interval-ms))
        (recur cur)))))
