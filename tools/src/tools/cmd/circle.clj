(ns tools.cmd.circle
  "The `circle` noun — the dev loop for the console: `console` runs the panels, `sim` fakes a
   fleet for them to find, `demo` is both in one command. The tree lives here; the work is
   tools.circle.console, tools.circle.sim and tools.circle.demo."
  (:require [tools.circle.console :as console]
            [tools.circle.demo    :as demo]
            [tools.circle.sim     :as sim]))


(def cli
  {"circle"
   {"console"
    {"up"
     {:usage "Usage: circle console up [<self-ip>] [--token <hex>]"
      :target console/up!
      :args [:self]
      :spec {:self  {:desc "Machine this console administers — its subnet is the one swept (default: a running sim's first machine)"
                     :coerce :string}
             :token {:desc "Circle token (default: the baked one)" :coerce :string}}}

     "down"
     {:usage "Usage: circle console down"
      :target console/down!
      :spec {}}}

    "sim"
    {"up"
     {:usage "Usage: circle sim up --range 192.168.1.200-229 [--token <hex>] [--seed n] [--pool p] [--skip-occupied]"
      :target sim/up!
      :spec {:range         {:desc "Addresses to claim, e.g. 192.168.1.200-229" :require true :coerce :string}
             :token         {:desc "Circle token the fakes hold (default: the baked one)" :coerce :string}
             :seed          {:desc "Seeds ids, serials and uptimes" :coerce :string}
             :pool          {:desc "Roster file" :coerce :string}
             :skip-occupied {:desc "Claim what is free instead of refusing" :coerce :boolean}}}

     "down"
     {:usage "Usage: circle sim down"
      :target sim/down!
      :spec {}}}

    "demo"
    {:usage "Usage: circle demo [<iface>] [--token <hex>]"
     :target demo/up!
     :args [:iface]
     :spec {:iface {:desc "Interface whose subnet the demo lands on (default: the first wifi device)"
                    :coerce :string}
            :token {:desc "Circle token (default: the baked one)" :coerce :string}}}}})
