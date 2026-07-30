(ns tools.cli
  "The host CLI, one bb task per noun: os (the 2-partition rootfs image), pack (the .pack
   artifact), disk (the full A/B disk), dev (a RUNNING device over ssh), loopback (loop-device
   utility). Each task passes its noun as the first token; the tree below is the whole surface.
   `bb pack <src> <out>` sugars to `pack make` (see pack-defaulted)."
  (:require
    [clojure.walk       :as walk]
    [lib.io             :as io]
    [lib.shell          :as shell]
    [lib.cli            :as cli]
    [lib.task           :as task]
    [tools.cmd.loopback :as loopback]
    [tools.cmd.pack     :as pack]
    [tools.cmd.image    :as image]
    [tools.cmd.disk     :as disk]
    [tools.cmd.stage    :as stage]
    [tools.cmd.dev      :as dev]))


(defn- stage-target! [{:keys [target] :as opts}]
  (stage/stage! target opts))


(def command-tree
  {"os"
   {"stage"
    {:usage "Usage: os stage <target>"
     :target stage-target!
     :args [:target]
     :spec {:target {:desc "Pinned base target (rpi-os)" :require true}}}

    "script"
    {:usage "Usage: os script <name> <img>"
     :target image/script! :args [:script :img]
     :spec {:script {:desc "os.<name> to run inside the chroot" :require true}
            :img    {:desc "OS image to customize" :require true}}}

    "chroot"
    {:usage "Usage: os chroot <img>"
     :target image/chroot-shell! :args [:img]
     :spec {:img {:desc "OS image to open an interactive chroot into" :require true}}}

    "initramfs"
    {:usage "Usage: os initramfs <img>"
     :target image/initramfs! :args [:img]
     :spec {:img {:desc "OS image to bake the prebuilt overlayroot initramfs into" :require true}}}

    "fetch"
    {:usage "Usage: os fetch <url> <out-img> [--sha256 <hex>]"
     :target image/fetch! :args [:url :out]
     :spec {:url    {:desc "Base image URL" :require true}
            :out    {:desc "Output .img path" :require true}
            :sha256 {:desc "Expected sha256 of the downloaded (compressed) file"}}}}

   "pack"
   {"make"
    {:usage "Usage: pack <img|blockdev> <out-pack> [--target <name>] [--arch <name>]"
     :target pack/make!
     :args [:src :out]
     :spec {:src    {:desc "Source OS image file or block device" :require true}
            :out    {:desc "Output .pack path" :require true}
            :target {:desc "Target name, e.g. rpi"}
            :arch   {:desc "Architecture name, e.g. arm64"}}}

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
    {:usage "Usage: disk ab create <scheme> <img|blockdev>"
     :target disk/ab!
     :args [:verb :scheme :target]
     :spec {:verb   {:desc "A/B verb (create)" :require true}
            :scheme {:desc "Boot scheme (autoboot)" :require true}
            :target {:desc "Disk medium: .img file or block device" :require true}}}

    "slot"
    {:usage "Usage: disk slot <A|B> from-pack <pack> <img|blockdev>\n       disk slot <A|B> activate <img|blockdev>"
     :target disk/slot!
     :args [:slot :verb :a :b]
     :spec {:slot {:desc "Slot: A or B" :require true}
            :verb {:desc "from-pack | activate" :require true}
            :a    {:desc "from-pack: the .pack | activate: the disk medium" :require true}
            :b    {:desc "from-pack: the disk medium"}}}

    "info"
    {:usage "Usage: disk info <img|blockdev>"
     :target disk/info!
     :args [:target]
     :spec {:target {:desc "Disk medium: .img file or block device" :require true}}}}

   "dev"
   {"push"
    {:usage "Usage: dev push ujimad <ip> [--user ujima] [--password ujima] [--port 22]"
     :target dev/push!
     :args [:target :ip]
     :spec {:target   {:desc "What to push (ujimad)" :require true}
            :ip       {:desc "Target RPI host or IP" :require true}
            :user     {:desc "SSH user"     :default "ujima"}
            :password {:desc "SSH password" :default "ujima"}
            :port     {:desc "SSH port"     :default "22"}}}

    "script"
    {:usage "Usage: dev script <name> <ip> [--user ujima] [--password ujima] [--port 22]"
     :target dev/script!
     :args [:script :ip]
     :spec {:script   {:desc "os.<name> to run live on the device" :require true}
            :ip       {:desc "Target RPI host or IP" :require true}
            :user     {:desc "SSH user"     :default "ujima"}
            :password {:desc "SSH password" :default "ujima"}
            :port     {:desc "SSH port"     :default "22"}}}

    "view"
    {:usage "Usage: dev view <ip> [--rfbport 5900] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target dev/view!
     :args [:ip]
     :spec {:ip       {:desc "Target RPI host or IP" :require true}
            :rfbport  {:desc "VNC/RFB port (tunneled over ssh)" :default "5900"}
            :display  {:desc "X display to mirror" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima"}
            :password {:desc "SSH password" :default "ujima"}
            :port     {:desc "SSH port"     :default "22"}}}

    "screenshot"
    {:usage "Usage: dev screenshot <ip> [--out ujima-screen.png] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target dev/screenshot!
     :args [:ip]
     :spec {:ip       {:desc "Target RPI host or IP" :require true}
            :out      {:desc "Host PNG output path" :default "ujima-screen.png"}
            :display  {:desc "X display to grab" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima"}
            :password {:desc "SSH password" :default "ujima"}
            :port     {:desc "SSH port"     :default "22"}}}

    "click"
    {:usage "Usage: dev click <x> <y> <ip> [--button 1] [--count 1] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target dev/click!
     :args [:x :y :ip]
     :spec {:x        {:desc "X coordinate on :0 (screenshot px = xdotool coord)" :require true}
            :y        {:desc "Y coordinate on :0" :require true}
            :ip       {:desc "Target RPI host or IP" :require true}
            :button   {:desc "Mouse button (1=left 2=mid 3=right)" :default "1"}
            :count    {:desc "Click count (2 = double-click)" :default "1"}
            :display  {:desc "X display" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima"}
            :password {:desc "SSH password" :default "ujima"}
            :port     {:desc "SSH port"     :default "22"}}}

    "type"
    {:usage "Usage: dev type <text> <ip> [--delay 40] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target dev/type!
     :args [:text :ip]
     :spec {:text     {:desc "Literal text to type on :0" :require true}
            :ip       {:desc "Target RPI host or IP" :require true}
            :delay    {:desc "ms between keystrokes" :default "40"}
            :display  {:desc "X display" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima"}
            :password {:desc "SSH password" :default "ujima"}
            :port     {:desc "SSH port"     :default "22"}}}

    "key"
    {:usage "Usage: dev key <chord> <ip> [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
     :target dev/key!
     :args [:chord :ip]
     :spec {:chord    {:desc "Key or chord, e.g. ctrl+f, Return, super+2" :require true}
            :ip       {:desc "Target RPI host or IP" :require true}
            :display  {:desc "X display" :default ":0"}
            :xauth    {:desc "Xauthority path on the device" :default "/home/ujima/.Xauthority"}
            :user     {:desc "SSH user"     :default "ujima"}
            :password {:desc "SSH password" :default "ujima"}
            :port     {:desc "SSH port"     :default "22"}}}}

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
      (if (and (map? node) (:target node))
        (update node :target wrap-target)
        node))
    tree))


(defn- pack-defaulted
  "`bb pack <src> <out>` sugar: a first pack argument that is not a known subcommand
   dispatches to `pack make`. The subcommand set is explicit — anything else is a source."
  [[noun sub :as args]]
  (if (and (= "pack" noun)
           sub
           (not (contains? #{"make" "validate" "meta" "-h" "--help"} sub)))
    (into ["pack" "make"] (rest args))
    args))


(defn -main
  [& [noun :as args]]

  (-> (io/slurp-config "tools/config" "tools")
      (get-in  [:shell :commands] {})
      (shell/install-remap!))

  (cli/dispatch! (wrap-targets (select-keys command-tree [noun]))
                 (pack-defaulted (vec args))))
