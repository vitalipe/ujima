(ns tools.cmd.circle
  "The `circle` noun — the dev loop for the console: `console` runs the panels, `sim` fakes a
   fleet for them to find. The tree lives here; starting the console is six lines, so it does
   too, while the sim's work is tools.circle.sim."
  (:require [babashka.process  :as p]
            [tools.circle.sim  :as sim]))


(defn- console-up!
  "The console in dev. The device hands it these two in the environment; here the CLI does."
  [{:keys [self token]}]
  (p/shell {:dir "desktop/console"
            :extra-env {"UJIMA_SELF"          self
                        "UJIMA_CIRCLE_TOKEN" (or token sim/default-token)}}
           "bb" "--config" (str (System/getProperty "user.dir") "/bb.edn")
           "-m" "console.main"))


(def command-tree
  {"console"
   {"up"
    {:usage "Usage: circle console up <self-ip> [--token <hex>]"
     :target console-up!
     :args [:self]
     :spec {:self  {:desc "Machine this console administers — its subnet is the one swept"
                    :require true :coerce :string}
            :token {:desc "Circle token (default: the baked one)" :coerce :string}}}}

   "sim"
   {"up"
    {:usage "Usage: circle sim up --range 192.168.1.200-229 [--token <hex>] [--seed n] [--pool p] [--skip-occupied]"
     :target sim/up!
     :spec {:range         {:desc "Addresses to claim, e.g. 192.168.1.200-229" :require true :coerce :string}
            :token         {:desc "Circle token the fakes hold (default: the baked one)" :coerce :string}
            :seed          {:desc "Seeds ids, serials and uptimes" :coerce :string}
            :pool          {:desc "Roster file" :coerce :string}
            :skip-occupied {:desc "Claim what is free instead of refusing" :coerce :boolean}}}

    "cleanup"
    {:usage "Usage: circle sim cleanup"
     :target sim/cleanup!
     :spec {}}}})
