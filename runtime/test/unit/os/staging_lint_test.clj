(ns os.staging-lint-test
  "Lints the os/<script>/<concern>/ staging tree against the scripts (runs from the repo
   root, reads sources as text). Enforces the two invariants of pull-based staging:
   every concern file is pulled by its owning script (a dropped-in file can never stage
   nothing, silently), and concern paths are referenced ONLY from their owning script
   (1:1 — os/<script>/ belongs to src/os/<script>.clj). README* files are docs, exempt.
   Coverage is by path prefix: a mirror!/copy-tree! literal like \"dev/kit\" covers the
   whole subtree."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]))


(def ^:private os-root  "os")
(def ^:private src-root "os/src/os")


(defn- script-dirs []
  (->> (fs/list-dir os-root)
       (filter fs/directory?)
       (map (comp str fs/file-name))
       (remove #{"src"})
       sort))

(defn- code-text
  "Script source with full-line comments dropped, so a path in prose can't satisfy
   (or violate) the lint."
  [f]
  (->> (str/split-lines (slurp f))
       (remove #(str/starts-with? (str/triml %) ";"))
       (str/join "\n")))

(defn- concern-files
  "Repo-relative-to-os/ paths of every real file under os/<dir>."
  [dir]
  (->> (fs/glob (str os-root "/" dir) "**" {:hidden true})
       (remove #(fs/directory? % {:nofollow-links true}))
       (map #(str (fs/relativize (fs/path os-root) %)))
       (remove #(str/starts-with? (str (fs/file-name %)) "README"))))

(defn- prefixes
  "\"base/x11/Xwrapper.config\" -> that, \"base/x11\" — every prefix down to
   <script>/<concern>, any of which a call-site literal may name."
  [rel]
  (let [parts (str/split rel #"/")]
    (map #(str/join "/" (take % parts))
         (range (count parts) 1 -1))))


(deftest every-concern-file-is-pulled-by-its-owning-script
  (doseq [dir (script-dirs)]
    (let [owner (str src-root "/" dir ".clj")]
      (is (fs/exists? owner)
          (str "concern dir os/" dir "/ has no owning script " owner))
      (when (fs/exists? owner)
        (let [text (code-text owner)]
          (doseq [rel (concern-files dir)]
            (is (some #(str/includes? text (str "\"" % "\"")) (prefixes rel))
                (str "os/" rel " is not pulled by " owner
                     " — orphan file, or the call site uses a non-literal path"))))))))


(deftest concern-paths-are-referenced-only-by-their-owning-script
  (doseq [dir  (script-dirs)
          file (fs/glob src-root "*.clj")
          :when (not= (str (fs/file-name file)) (str dir ".clj"))]
    (is (not (str/includes? (code-text (str file)) (str "\"" dir "/")))
        (str (fs/file-name file) " references os/" dir "/ — that dir belongs to "
             dir ".clj (1:1 ownership)"))))
