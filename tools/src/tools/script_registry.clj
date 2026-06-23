(ns tools.script-registry
  "Single source of truth for the tools.scripts.<name> registry: where the scripts live on disk
   and fail-fast validation that a named one exists. Shared by `tools image script` (runs it in
   the build chroot) and `tools dev script` (runs it live on a device) so both agree on the
   available set and reject typos identically — before any chroot/loopback/ssh work."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]))


(def scripts-dir "tools/src/tools/scripts")  ;; host-side, repo-relative


(defn available
  "Sorted vector of script names (the <name> in tools.scripts.<name>) discoverable on disk."
  []
  (->> (fs/glob scripts-dir "*.clj")
       (mapv #(str/replace (str (fs/file-name %)) #"\.clj$" ""))
       sort vec))


(defn require-script!
  "Throw (listing what's available) if tools.scripts.<script> doesn't exist."
  [script]
  (when-not (fs/exists? (fs/path scripts-dir (str script ".clj")))
    (throw (ex-info (str "Unknown script: " script)
                    {:script script :available (available)}))))
