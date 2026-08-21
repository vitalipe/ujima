(ns tools.cli
  "The host CLI, one bb task per noun: build (artifacts: a pack, or a provisioned disk),
   stage (an image from the pinned base), os (the 2-partition rootfs image), pack (the .pack
   artifact), disk (the full A/B disk, one subtree per boot scheme), dev (a RUNNING device
   over ssh), loopback (loop-device utility), circle (the console dev loop). Each noun's surface lives with its own tools.cmd.* ns as `cli` and is
   merged here; os is the exception, its verbs belonging to the image builder. Nesting is
   free — a node with a :target is a command, anything else groups them.
   `bb pack <src> <out>` sugars to `pack make` (see pack-defaulted)."
  (:require
    [clojure.walk       :as walk]
    [lib.io             :as io]
    [lib.shell          :as shell]
    [lib.cli            :as cli]
    [lib.task           :as task]
    [tools.cmd.loopback :as loopback]
    [tools.cmd.pack     :as pack]
    [build.image        :as image]
    [tools.cmd.disk     :as disk]
    [tools.cmd.stage    :as stage]
    [tools.cmd.build    :as build]
    [tools.cmd.dev      :as dev]
    [tools.cmd.pin      :as pin]
    [tools.cmd.circle   :as circle]))


(defn- merge-nouns
  "Each tools.cmd.* ns contributes exactly one noun. A map handed over at the wrong depth
   merges silently — its inner keys just become nouns nothing dispatches — which is how
   `bb circle` once came up with an empty command list. Fail at load instead."
  [& contributions]
  (doseq [c contributions]
    (assert (and (map? c) (= 1 (count c)))
            (str "a command ns must contribute exactly one noun, got " (pr-str (keys c)))))
  (let [dupes (->> contributions (mapcat keys) frequencies (keep (fn [[n c]] (when (> c 1) n))))]
    (assert (empty? dupes) (str "two namespaces claim the same noun: " (pr-str (vec dupes)))))
  (apply merge contributions))


(def command-tree
  (merge-nouns
    build/cli
    stage/cli
    pack/cli
    disk/cli
    circle/cli
    dev/cli
    pin/cli
    loopback/cli

    ;; os is the exception: its verbs are the image builder's, so there is no
    ;; tools.cmd.os to carry them
    {"os"
     {"apply"
      {:usage "Usage: os apply <img> [--dev]"
       :target image/apply!
       :args [:img]
       :spec {:img {:desc "Staged OS image to apply the ujima content chain into" :require true}
              :dev {:coerce :boolean
                    :desc "Bake the dev rig (ssh/vnc/xdotool) and skip cleanup"}}}

      "script"
      {:usage "Usage: os script <img> <name>"
       :target image/script! :args [:img :script]
       :spec {:img    {:desc "OS image to customize" :require true}
              :script {:desc "pipeline script to run inside the chroot" :require true}}}

      "chroot"
      {:usage "Usage: os chroot <img>"
       :target image/chroot-shell! :args [:img]
       :spec {:img {:desc "OS image to open an interactive chroot into" :require true}}}}}))


;; ----------------------------------------------------------------------------
;; Task rendering — a target that returns a lib.task flow is run + rendered via
;; lib.cli/run-and-display!; any other return value passes straight through.
;; ----------------------------------------------------------------------------

(defn- wrap-target [target]
  (fn [opts]
    (let [result (target opts)]
      (if (task/task? result)
        (cli/run-and-display! result)
        result))))


(defn- wrap-targets
  "Wrap every command target so a returned task is run + rendered."
  [tree]
  (walk/postwalk
    (fn [node]
      (if (cli/command-node? node)
        (update node :target wrap-target)
        node))
    tree))


;; bare-noun sugar: `bb pack <src> <out>` / `bb stage <target>` — a first argument that
;; is not a known subcommand dispatches to the noun's make-verb. Subcommand sets are
;; explicit; anything else is treated as the default verb's first positional.
(def ^:private default-verbs
  {"pack"  {:verb "make" :subs #{"make" "validate" "meta"}}
   "stage" {:verb "run"  :subs #{"run"}}
})

(defn- with-default-verb [[noun sub :as args]]
  (let [{:keys [verb subs]} (get default-verbs noun)]
    (if (and verb sub
             (not (contains? (into #{"-h" "--help"} subs) sub)))
      (into [noun verb] (rest args))
      args)))


(defn -main
  [& [noun :as args]]

  (-> (io/slurp-config "tools/config" "tools")
      (get-in  [:shell :commands] {})
      (shell/install-remap!))

  (cli/dispatch! (wrap-targets (select-keys command-tree [noun]))
                 (with-default-verb (vec args))))
