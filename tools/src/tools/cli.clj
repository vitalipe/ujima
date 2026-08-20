(ns tools.cli
  "The host CLI, one bb task per noun: build (the whole pipeline), stage (an image from the
   pinned base), os (the 2-partition rootfs image), pack (the .pack artifact), disk (the full
   A/B disk), dev (a RUNNING device over ssh), loopback (loop-device utility). Each task passes
   its noun as the first token; the tree below is the whole surface.
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
    [tools.cmd.circle   :as circle]))


(defn- stage-target! [{:keys [target] :as opts}]
  (stage/stage! target opts))


(def command-tree
  {"build"
   {"run"
    {:usage "Usage: build <target> [--dev]"
     :target build/build!
     :args [:target]
     :spec {:target {:desc "Build target (rpi-os)" :require true}
            :dev    {:coerce :boolean
                     :desc "Bake the dev rig (ssh/vnc/xdotool) and skip cleanup"}}}}

   "stage"
   {"run"
    {:usage "Usage: stage <target>"
     :target stage-target!
     :args [:target]
     :spec {:target {:desc "Pinned base target (rpi-os)" :require true}}}}

   "os"
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
     :spec {:img {:desc "OS image to open an interactive chroot into" :require true}}}}

   "pack"
   {"make"
    {:usage "Usage: pack <img|blockdev> <out-pack>"
     :target pack/make!
     :args [:src :out]
     :spec {:src {:desc "Source OS image file or block device" :require true}
            :out {:desc "Output .pack path" :require true}}}

    "validate"
    {:usage "Usage: pack validate <pack>"
     :target pack/validate-pack!
     :args [:ujima-pack-path]
     :spec {:ujima-pack-path {:desc "Ujima pack path" :require true}}}

    "meta"
    {:usage "Usage: pack meta <pack> [--format edn|json]"
     :target pack/print-pack-meta!
     :args [:ujima-pack-path]
     :spec {:ujima-pack-path {:desc "Ujima pack path" :require true}
            :format {:desc "Output format: edn or json"
                     :default "edn"
                     :validate #{"edn" "json"}}}}}

   "disk"
   {"ab"
    {"create"
     {:usage "Usage: disk ab create <scheme> <img|blockdev>"
      :target disk/ab-create!
      :args [:scheme :target]
      :spec {:scheme {:desc "Boot scheme (autoboot)" :require true}
             :target {:desc "Disk target: .img file or block device" :require true}}}}

    "slot"
    {:usage "Usage: disk slot <A|B> from-pack <pack> <img|blockdev>\n       disk slot <A|B> from-image <img> <img|blockdev>\n       disk slot <A|B> activate <img|blockdev>"
     :target disk/slot!
     :args [:slot :verb :a :b]
     :spec {:slot {:desc "Slot: A or B" :require true}
            :verb {:desc "from-pack | activate" :require true}
            :a    {:desc "from-pack: the .pack | activate: the disk target" :require true}
            :b    {:desc "from-pack: the disk target"}}}

    "info"
    {:usage "Usage: disk info <img|blockdev>"
     :target disk/info!
     :args [:target]
     :spec {:target {:desc "Disk target: .img file or block device" :require true}}}}

   "circle" circle/command-tree

   "dev"
   {"push"
    {:usage "Usage: dev push <ip> ujimad [--user ujima] [--password ujima] [--port 22]"
     :target dev/push!
     :args [:ip :target]
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :target   {:desc "What to push (ujimad)" :require true :coerce :string}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}

    "script"
    {:usage "Usage: dev script <ip> <name> [--user ujima] [--password ujima] [--port 22]"
     :target dev/script!
     :args [:ip :script]
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :script   {:desc "pipeline script to run live on the device" :require true :coerce :string}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}

    "view"
    {:usage "Usage: dev view <ip> [--rfbport 5900] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target dev/view!
     :args [:ip]
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :rfbport  {:desc "VNC/RFB port (tunneled over ssh)" :default "5900" :coerce :string}
            :display  {:desc "X display to mirror" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}

    "screenshot"
    {:usage "Usage: dev screenshot <ip> [--out tmp/screen/ujima-screen.png] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target dev/screenshot!
     :args [:ip]
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :out      {:desc "Host PNG output path" :default "tmp/screen/ujima-screen.png"}
            :display  {:desc "X display to grab" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}

    "click"
    {:usage "Usage: dev click <ip> <x> <y> [--button 1] [--count 1] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target dev/click!
     :args [:ip :x :y]
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :x        {:desc "X coordinate on :0 (screenshot px = xdotool coord)" :require true :coerce :string}
            :y        {:desc "Y coordinate on :0" :require true :coerce :string}
            :button   {:desc "Mouse button (1=left 2=mid 3=right)" :default "1" :coerce :string}
            :count    {:desc "Click count (2 = double-click)" :default "1" :coerce :string}
            :display  {:desc "X display" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}

    "type"
    {:usage "Usage: dev type <ip> <text> [--delay 40] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target dev/type!
     :args [:ip :text]
     ;; :coerce :string or babashka.cli parses digit-only args as numbers — `dev type <ip> 42`
     ;; then dies in the arg handling instead of typing "42"
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :text     {:desc "Literal text to type on :0" :require true :coerce :string}
            :delay    {:desc "ms between keystrokes" :default "40" :coerce :string}
            :display  {:desc "X display" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}

    "key"
    {:usage "Usage: dev key <ip> <chord> [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target dev/key!
     :args [:ip :chord]
     :spec {:ip       {:desc "Target RPI host or IP" :require true :coerce :string}
            :chord    {:desc "Key or chord, e.g. ctrl+f, Return, super+2" :require true :coerce :string}
            :display  {:desc "X display" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima" :coerce :string}
            :password {:desc "SSH password" :default "ujima" :coerce :string}
            :port     {:desc "SSH port"     :default "22"    :coerce :string}}}}

   "loopback"
   {"attach"
    {:usage "Usage: loopback attach <img-file-path> [--readonly]"
     :target loopback/attach-loopback!
     :args [:img-file-path]
     :spec {:img-file-path {:desc "Image file path" :require true}
            :readonly {:coerce :boolean :desc "Attach image read-only"}}}

    "detach"
    {:usage "Usage: loopback detach <img-file-or-loop-device>"
     :target loopback/detach-loopback!
     :args [:img-file-or-loop-device]
     :spec {:img-file-or-loop-device {:desc "Image path or loop device path" :require true}}}

    "list"
    {:usage "Usage: loopback list"
     :target loopback/list-loopbacks!
     :args []
     :spec {}}}})


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


;; bare-noun sugar: `bb pack <src> <out>` / `bb build <target>` — a first argument that
;; is not a known subcommand dispatches to the noun's make-verb. Subcommand sets are
;; explicit; anything else is treated as the default verb's first positional.
(def ^:private default-verbs
  {"pack"  {:verb "make" :subs #{"make" "validate" "meta"}}
   "build" {:verb "run" :subs #{"run"}}
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
