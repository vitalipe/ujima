(ns ujima.target.rpi.autoboot
  "Helpers for reading and writing Raspberry Pi boot-slot files.

   This namespace does not mount partitions. Callers pass concrete mounted directory paths,
   usually inside an already-mounted boot partition.

   Examples:

     (cmdline \"/mnt/boot-a/\")
     (cmdline! \"/mnt/boot-a/\" \"/dev/mmcblk0p5\")

     (autoboot \"/mnt/control\")
     (autoboot! \"/mnt/control/\" {:boot 1 :try-boot 2})"

  (:require [clojure.string :as str]
            [babashka.fs :as fs]

            [ujima.fs :refer [spit-file-atomic! file->number slurp-text]]))


(defn- parse-int [s] (when s (parse-long s)))


(defn cmdline
  "Read `cmdline.txt` from `path` and return the configured root block
   device from the `root=` kernel argument, or nil if no `root=` argument exists.
   
   Note: use PARTUUID

   Example return value:

     \"PARTUUID=13371337-05\""
  [path]
  
  (let [content (slurp-text (fs/path path "cmdline.txt"))]
    (some->> content
             (re-find #"(^|\s)root=(\S+)")
             (nth 2 nil))))


(defn cmdline!
  "Write a Raspberry Pi `cmdline.txt` file at `path` so it boots from `target-block-device`.
   Prefer PARTUUID over block device paths, device paths might fail with offline installs! 

   Returns the written commandline state (cmdline) "
  [path target-block-device]

  (spit-file-atomic! (fs/path path "cmdline.txt") 
                     (str "console=serial0,115200 console=tty1"
                          " root=" target-block-device
                          " rootfstype=ext4 fsck.repair=yes rootwait quiet splash"))

  (cmdline path))


(defn autoboot
  "Read Raspberry Pi `autoboot.txt` from `path`.
   
   Note: autoboot.txt is not zero is not zero-based, values range (1-4) 

   Returns a map like:

     {:boot 1 :try-boot 2}

   `:try-boot` may be nil"
  [path]
  (let [kv-line (fn [line]
                 (let [[_ k v] (re-matches #"^\s*([^#=\s]+)\s*=\s*([^#\s]+).*$" line)]
                   (when k [(keyword k) (parse-int v)])))

        {:keys [boot_partition 
                tryboot_partition 
                tryboot_a_b]} (->> "autoboot.txt"  
                                (fs/path path)
                                (slurp-text)
                                (str/split-lines)
                                (keep kv-line)
                                (into {}))]
    
    (cond
       tryboot_a_b {:boot boot_partition :try-boot tryboot_partition}
       :no-tryboot {:boot boot_partition :try-boot nil})))


(defn autoboot!
 "Write Raspberry Pi `autoboot.txt` to `path`.

   Example:

      autoboot! path {:boot 1       ; index of bootfs  
                      :try-boot 2}  ; index of bootfs to try-boot or nil

   Returns the updated file content."
  
  [path {:keys [boot try-boot] :or {boot 2}}]
  
  ;; autoboot.txt is an ini file that looks like this:
  ;; [all]
  ;; tryboot_a_b=1
  ;; boot_partition=2
  ;; 
  ;; [tryboot]
  ;; boot_partition=3 

  (spit-file-atomic! (fs/path path "autoboot.txt") 
                     (cond
                       (nil? try-boot) (str "[all]\n" 
                                            "boot_partition=" boot "\n")
                       :with-try-boot  (str "[all]\n" 
                                            "tryboot_a_b=1\n" 
                                            "boot_partition=" boot "\n"
                                            "\n"
                                            "[tryboot]\n"
                                            "boot_partition=" try-boot "\n")))
  (autoboot path))


(defn running-in-tryboot? []
  (= 1 (file->number "/proc/device-tree/chosen/bootloader/tryboot")))
