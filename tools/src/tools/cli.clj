(ns tools.cli
  (:require
    [clojure.walk       :as walk]
    [lib.io             :as io]
    [lib.shell          :as shell]
    [lib.cli            :as cli]
    [lib.task           :as task]
    [tools.cmd.loopback :as loopback]
    [tools.cmd.pack     :as pack]
    [tools.cmd.image    :as image]
    [tools.cmd.stage    :as stage]
    [tools.cmd.dev      :as dev]))



(def command-tree
  {"stage"
   {"rpi-os"
    {:usage "Usage: tools stage rpi-os"
     :target stage/rpi-os!
     :args []
     :spec {}}}

   "dev"
   {"push"
    {:usage "Usage: tools dev push agent <ip> [--user ujima] [--password ujima] [--port 22]"
     :target dev/push!
     :args [:target :ip]
     :spec {:target   {:desc "What to push (agent)" :require true}
            :ip       {:desc "Target RPI host or IP" :require true}
            :user     {:desc "SSH user"     :default "ujima"}
            :password {:desc "SSH password" :default "ujima"}
            :port     {:desc "SSH port"     :default "22"}}}

    "script"
    {:usage "Usage: tools dev script <name> <ip> [--user ujima] [--password ujima] [--port 22]"
     :target dev/script!
     :args [:script :ip]
     :spec {:script   {:desc "Script to run live (tools.scripts.<name>)" :require true}
            :ip       {:desc "Target RPI host or IP" :require true}
            :user     {:desc "SSH user"     :default "ujima"}
            :password {:desc "SSH password" :default "ujima"}
            :port     {:desc "SSH port"     :default "22"}}}

    "view"
    {:usage "Usage: tools dev view <ip> [--rfbport 5900] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
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
    {:usage "Usage: tools dev screenshot <ip> [--out ujima-screen.png] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
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
    {:usage "Usage: tools dev click <x> <y> <ip> [--button 1] [--count 1] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
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
    {:usage "Usage: tools dev type <text> <ip> [--delay 40] [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
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
    {:usage "Usage: tools dev key <chord> <ip> [--display :0] [--xauth /home/ujima/.Xauthority] [--user ujima] [--password ujima] [--port 22]"
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
    {:usage "Usage: tools loopback attach <img-file-path> [--readonly]"
     :target loopback/attach-loopback!
     :args [:img-file-path]
     :spec {:img-file-path {:desc "Image file path"
                            :require true}
            :readonly {:coerce :boolean
                       :desc "Attach image read-only"}}}

    "detach"
    {:usage "Usage: tools loopback detach <img-file-or-loop-device>"
     :target loopback/detach-loopback!
     :args [:img-file-or-loop-device]
     :spec {:img-file-or-loop-device {:desc "Image path or loop device path"
                                      :require true}}}

    "list"
    {:usage "Usage: tools loopback list"
     :target loopback/list-loopbacks!
     :args []
     :spec {}}}

   "pack"
   {"device"
    {:usage "Usage: tools pack device <block-device> <out-pack> [--target <target-name>] [--arch <arch-name>]"
     :target pack/pack-device!
     :args [:device :out]
     :spec {:device {:desc "Source block device path"
                     :require true}
            :out    {:desc "Output Ujima pack path"
                     :require true}
            :target {:desc "Target name, e.g. rpi"}
            :arch   {:desc "Architecture name, e.g. arm64"}}}

    "image"
    {:usage "Usage: tools pack image <img> <out-pack> [--target <target-name>] [--arch <arch-name>]"
     :target pack/pack-image!
     :args [:img :out]
     :spec {:img    {:desc "Source image file (loopback-attached, then packed)"
                     :require true}
            :out    {:desc "Output Ujima pack path"
                     :require true}
            :target {:desc "Target name, e.g. rpi"}
            :arch   {:desc "Architecture name, e.g. arm64"}}}

    "validate"
    {:usage "Usage: tools pack validate <ujima-pack-path>"
     :target pack/validate-pack!
     :args [:ujima-pack-path]
     :spec {:ujima-pack-path {:desc "Ujima pack path"
                              :require true}}}

    "meta"
    {:usage "Usage: tools pack meta <ujima-pack-path> [--format edn|json]"
     :target pack/print-pack-meta!
     :args [:ujima-pack-path]
     :spec {:ujima-pack-path {:desc "Ujima pack path"
                              :require true}
            :format {:desc "Output format: edn or json"
                     :default "edn"
                     :validate #{"edn" "json"}}}}}

   "image"
   {"fetch"
    {:usage "Usage: tools image fetch <url> <out-img> [--sha256 <hex>]"
     :target image/fetch! :args [:url :out]
     :spec {:url    {:desc "Base image URL" :require true}
            :out    {:desc "Output .img path" :require true}
            :sha256 {:desc "Expected sha256 of the downloaded (compressed) file"}}}

    "script"
    {:usage "Usage: tools image script <img> <script>"
     :target image/script! :args [:img :script]
     :spec {:script {:desc "Script to run inside chroot" :require true}
            :img    {:desc "Image to customize" :require true}}}

    "chroot"
    {:usage "Usage: tools image chroot <img>"
     :target image/chroot-shell! :args [:img]
     :spec {:img {:desc "Image to open an interactive chroot into" :require true}}}

    "from-pack"
    {:usage "Usage: tools image from-pack <pack> <out-img> [--layout autoboot]"
     :target image/from-pack! :args [:pack :out]
     :spec {:pack   {:desc "Source .pack" :require true}
            :out    {:desc "Output .img" :require true}
            :layout {:desc "Disk layout" :default "autoboot" :validate #{"autoboot"}}}}

    "initramfs"
    {:usage "Usage: tools image initramfs <img>"
     :target image/initramfs! :args [:img]
     :spec {:img {:desc "Image to bake the prebuilt overlayroot initramfs into" :require true}}}}})


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


(defn -main
  [& args]

  (-> (io/slurp-config "config" "tools")
      (get-in  [:shell :commands] {})
      (shell/install-remap!))

  (cli/dispatch! (wrap-targets command-tree) args))
