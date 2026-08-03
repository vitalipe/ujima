(ns os.lib.stage
  "Pull-based staging of static device files from the per-script concern dirs:
   os/<script>/<concern>/<file>, staged by an explicit call in src/os/<script>.clj — the
   source path names the file once, the call site names the destination. Ownership is
   1:1 (a concern dir belongs to exactly one script) and lint-enforced
   (os.staging-lint-test). Files land root-owned unless :owner says otherwise — cp -a
   alone would carry the repo's build-host uids onto the device."
  (:require [babashka.fs :as fs]
            [lib.shell :refer [$!]]))


(defn source
  "Absolute path of a concern source file — for call sites that READ a source rather
   than copy it (e.g. desktop's i18n catalog). Throws on a missing file, like every
   staging call: a typo must never stage nothing, silently."
  [project src]
  (let [s (str project "/os/" src)]
    (when-not (fs/exists? s)
      (throw (ex-info "os.lib.stage: missing source file" {:src src :path s})))
    s))


(defn install!
  "One file: <project>/os/<src> -> absolute <dst>. Preserves the exec bit (cp -a);
   :mode for the rest (sudoers 0440), :owner when not root:root."
  ([project src dst] (install! project src dst {}))
  ([project src dst {:keys [mode owner]}]
   (let [s (source project src)]
     (fs/create-dirs (fs/parent dst))
     ($! cp -a [s] [dst])
     ($! chown [(or owner "root:root")] [dst])
     ;; deterministic modes — cp -a alone would ship the build host's umask
     ($! chmod [(or mode (if (fs/executable? s) "0755" "0644"))] [dst])
     (println "staged" src "->" dst))))


(defn mirror!
  "A dir the concern owns wholesale: delete-then-copy, so a file removed from the
   concern disappears from the device on live re-runs."
  ([project src dst] (mirror! project src dst {}))
  ([project src dst {:keys [owner]}]
   (let [s (source project src)]
     ($! rm -rf [dst])
     (fs/create-dirs (fs/parent dst))
     ($! cp -a [s] [dst])
     ($! chown -R [(or owner "root:root")] [dst])
     (println "mirrored" src "->" dst))))


(defn copy-tree!
  "Merge a tree onto an existing area (e.g. /home/ujima): a dir is only created when
   missing — an existing dir's ownership/mode is NEVER touched (cp -a of a tree root
   re-owns every dir it merges through; it root-owned /home/ujima on HW once). With
   :owner, everything the tree brought — the src dir's direct children, recursively —
   is chowned; the area itself never is."
  ([project src dst] (copy-tree! project src dst {}))
  ([project src dst {:keys [owner]}]
   (let [s (source project src)]
     (doseq [p (sort-by str (fs/glob s "**" {:hidden true}))
             :let [target (str dst "/" (fs/relativize s p))]]
       (if (fs/directory? p {:nofollow-links true})
         (fs/create-dirs target)
         ($! cp -a [(str p)] [target])))
     (when owner
       (doseq [c (fs/list-dir s)]
         ($! chown -R [owner] [(str dst "/" (fs/file-name c))])))
     (println "merged" src "->" dst))))
