(ns tools.repl
  (:require
    [clojure.main :as main]
    [clojure.pprint :refer [pprint]]
    [babashka.fs :as fs]

    [ujima.env  :as env]
    [e2e.runner :as e2e]

    [ujima.linux.shell :refer [$! sudo$! sh! sudo!]]
    [ujima.linux.disk  :as disk]
    [ujima.deploy.pack :as pack]
    [ujima.task.flow   :as task]

    [tools.cli.loopback :as +loop]
    [tools.cli.pack     :as +pack]))


(def tmp$ (fs/path "tmp" "ujima" "repl"))


(defn ensure-tmp! []
  (fs/create-dirs tmp$)
  tmp$)


(defn clean-tmp! []
  (when (fs/exists? tmp$)
    (fs/delete-tree tmp$))
  (ensure-tmp!))


(defn ls [& path]
  (->> path
     (apply fs/path tmp$) 
     (fs/list-dir)
     (map fs/file-name)))

  
(defn e2e! [test-name & args]
  (e2e/run-test! (name test-name) 
                 (fs/path tmp$ "e2e" (name test-name))  
                 args))


(defn help []
  (println)
  (println "Ujima dev REPL")
  (println "==============")
  (println)
  (println "Loaded aliases:")
  (println "  e2e    => e2e.runner")
  (println "  disk   => ujima.linux.disk")
  (println "  pack   => ujima.deploy.pack")
  (println "  task   => ujima.task.flow")
  (println "  +loop  => tools.cli.loopback")
  (println "  +pack  => tools.cli.pack")
  (println)
  (println "Referred shell helpers:")
  (println "  $! sudo$! sh! sudo!")
  (println)
  (println "Shared REPL tmp dir:")
  (println "  tmp$         =>" (str tmp$))
  (println "  ensure-tmp!  => create tmp$ if missing")
  (println "  clean-tmp!   => delete and recreate tmp$")
  (println "  ls           => list files under tmp$")
  (println)
  (println "E2E helpers:")
  (println "  (e2e! \"cli\")")
  (println "  (e2e! :cli)")
  (println "  (e2e! \"cli\" \"--keep\")")
  (println "  tmp dir used: tmp$/e2e/<test-name>")
  (println))


(defn usage []
  (println)
  (println "Ujima dev REPL")
  (println "==============")
  (println)
  (println "Examples:")
  (println "  ($! bb tools loopback list)")
  (println "  (+loop/list-loopbacks! {})")
  (println "  (+pack/validate-pack! {:ujima-pack-path \"tmp/u.pack\"})")
  (println "  (pack/metadata \"tmp/u.pack\")")
  (println "  (disk/device->partitions \"/dev/loop0\")")
  (println "  (ls)")
  (println "  (ls \"e2e\")")
  (println))


(defn start! []
  (env/init! ["config/ujima.edn"
              "config/config.local.edn"])
  (ensure-tmp!)

  (println)
  (println "Loaded tools.repl")
  (println "Run (help) for available aliases.")
  (println "Run (usage) for see usage examples.")

  (println "tmp$ =" (str tmp$))
  (println)

  ;; Put the interactive REPL in this namespace.
  (in-ns 'tools.repl)

  (main/repl
    :prompt #(print "tools.repl=> ")))
