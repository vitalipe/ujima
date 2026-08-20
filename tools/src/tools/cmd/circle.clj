(ns tools.cmd.circle
  "The `circle` noun — the dev loop for the console: `console` runs the panels, `sim` fakes a
   fleet for them to find.

   lib.cli's tree is exactly two levels, so the third is a positional verb this ns switches
   on. Dispatch only; the sim's work lives in tools.circle.sim, and starting the console is
   small enough to sit here."
  (:require [clojure.string    :as str]
            [babashka.process  :as p]
            [tools.circle.sim  :as sim]))


(defn- verb! [what verb table opts]
  (if-let [run (get table verb)]
    (run opts)
    (throw (ex-info (str "circle " what " takes " (str/join " or " (sort (keys table)))
                         ", got: " (or verb "nothing"))
                    {:verb verb}))))


(defn console!
  "The console in dev. The device hands it these two in the environment; here the CLI does."
  [{:keys [verb self token] :as opts}]
  (verb! "console" verb
         {"up" (fn [_]
                 (when-not self
                   (throw (ex-info "circle console up needs the ip of the machine to administer" {})))
                 (p/shell {:dir "desktop/console"
                           :extra-env {"UJIMA_SELF"          self
                                       "UJIMA_CIRCLE_TOKEN" (or token sim/default-token)}}
                          "bb" "--config" (str (System/getProperty "user.dir") "/bb.edn")
                          "-m" "console.main"))}
         opts))


(defn sim!
  [{:keys [verb] :as opts}]
  (verb! "sim" verb {"up" sim/up! "cleanup" sim/cleanup!} opts))
