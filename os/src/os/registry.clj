(ns os.registry
  "Single source of truth for the os.<name> script registry: where the scripts live on disk
   and fail-fast validation that a named one exists. Shared by `bb os script` (runs it in
   the build chroot) and `bb dev script` (runs it live on a device) so both agree on the
   available set and reject typos identically — before any chroot/loopback/ssh work."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]))


(def scripts-dir "os/src/os")  ;; host-side, repo-relative


(defn available
  "Sorted vector of script names (the <name> in os.<name>) discoverable on disk."
  []
  (->> (fs/glob scripts-dir "*.clj")
       (mapv #(str/replace (str (fs/file-name %)) #"\.clj$" ""))
       (remove #{"registry"})
       sort vec))


(defn require-script!
  "Throw (listing what's available) if os.<script> doesn't exist."
  [script]
  (when-not (and (not= "registry" (str script))
                 (fs/exists? (fs/path scripts-dir (str script ".clj"))))
    (throw (ex-info (str "Unknown script: " script)
                    {:script script :available (available)}))))
