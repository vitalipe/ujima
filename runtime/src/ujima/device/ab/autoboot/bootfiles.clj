(ns ujima.device.ab.autoboot.bootfiles
  "Helpers for reading and writing Raspberry Pi boot-slot files.

   This namespace does not mount partitions. Callers pass concrete mounted directory paths,
   usually inside an already-mounted boot partition.

   Examples:

     (cmdline \"/mnt/boot-a/\")
     (cmdline! \"/mnt/boot-a/\" params)

     (autoboot \"/mnt/control\")
     (autoboot! \"/mnt/control/\" {:boot 1 :try-boot 2})"

  (:require [clojure.string :as str]
            [babashka.fs :as fs]

            [lib.io :refer [spit-file-atomic! slurp-text]]))


(defn- parse-int [s] (when s (parse-long s)))


(defn- slurp-ini! [path]
  (loop [[line & more] (str/split-lines (slurp-text path))
         section       nil
         result        {}]
    (if-not line
      result
      (let [[_ next-section] (re-matches #"^\s*\[([^\]\s]+)\]\s*(?:#.*)?$" line)
            [_ k v]          (re-matches #"^\s*([^#=\s]+)\s*=\s*([^#\s]+).*$" line)]
        (cond
          next-section (recur more next-section result)
          k            (recur more section (assoc-in result [section k] v))
          :otherwise   (recur more section result))))))


(defn cmdline
  "Read `cmdline.txt` from `path` as [key value] pairs, value nil for a bare token.
   A vector, not a map: `console` legitimately appears twice, and order is preserved."
  [path]

  (->> (str/split (str/trim (slurp-text (fs/path path "cmdline.txt"))) #"\s+")
       (remove str/blank?)
       (mapv (fn [token]
               (let [[k v] (str/split token #"=" 2)]
                 [k v])))))


(defn cmdline-get
  "First value for `k`, nil when absent or bare."
  [params k]
  (some (fn [[pk pv]] (when (= pk k) pv)) params))


(defn cmdline-assoc
  "Set `k` to `v` (nil = bare token) in place, appending when absent."
  [params k v]
  (if (some (fn [[pk _]] (= pk k)) params)
    (mapv (fn [[pk _ :as p]] (if (= pk k) [k v] p)) params)
    (conj (vec params) [k v])))


(defn cmdline!
  "Write [key value] `params` as `cmdline.txt` at `path`.
   Prefer PARTUUID over block device paths, device paths might fail with offline installs!

   Returns the written commandline state (cmdline) "
  [path params]

  (spit-file-atomic! (fs/path path "cmdline.txt")
                     (str/join " " (map (fn [[k v]] (if v (str k "=" v) k)) params)))

  (cmdline path))


(defn autoboot
  "Read Raspberry Pi `autoboot.txt` from `path`.
   
   Note: autoboot.txt is not zero is not zero-based, values range (1-4) 

   Returns a map like:

     {:boot 1 :try-boot 2}

  `:try-boot` may be nil"
  [path]
  (let [ini          (slurp-ini! (fs/path path "autoboot.txt"))
        all-boot     (get-in ini ["all" "boot_partition"])
        tryboot-boot (get-in ini ["tryboot" "boot_partition"])
        tryboot-a-b  (get-in ini ["all" "tryboot_a_b"])]
    
    (cond
       tryboot-a-b {:boot (parse-int all-boot) :try-boot (parse-int tryboot-boot)}
       :no-tryboot {:boot (parse-int all-boot) :try-boot nil})))


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
  