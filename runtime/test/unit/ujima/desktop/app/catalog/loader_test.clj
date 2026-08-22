(ns ujima.desktop.app.catalog.loader-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [ujima.desktop.app.catalog.loader :as loader]))


(defn- scan-root!
  "Materialize {dir-name spec} as a temp scan root: <root>/<dir>/app.edn per entry."
  [apps]
  (let [root (fs/create-temp-dir {:prefix "ujima-apps"})]
    (doseq [[id spec] apps]
      (fs/create-dirs (fs/path root id))
      (spit (str (fs/path root id "app.edn")) (pr-str spec)))
    (str root)))


;; --- catalog scan: dirs with app.edn, abc order, app-OS split (bad content never throws) ---

(deftest scan-ids-from-dir-names-in-abc-order
  (let [root (scan-root! {"zebra" {:kind :exec :label "Z" :exec ["z"]}
                          "alpha" {:kind :exec :label "A" :exec ["a"]}
                          "mango" {:kind :exec :label "M" :exec ["m"]}})]
    (fs/create-dirs (fs/path root "payload-only"))            ; no app.edn -> not an app
    (spit (str (fs/path root "stray.txt")) "not a dir")
    (spit (str (fs/path root "alpha" "icon.svg")) "<svg/>")   ; alpha owns its face
    (let [c (loader/load-catalog [root] "fallback-launcher.svg")]
      (is (= [:alpha :mango :zebra] (:order c)) "dir name = id, abc = launcher order")
      (is (= "A" (get-in c [:by-id :alpha :label])))
      (is (= (str (fs/path root "alpha" "icon.svg")) (get-in c [:by-id :alpha :icon]))
          "the app dir's icon.svg is the icon")
      (is (clojure.string/ends-with? (get-in c [:by-id :mango :icon]) "launcher.svg")
          "no icon.svg -> the fallback glyph path"))
    (fs/delete-tree root)))

(deftest scan-skips-broken-apps-and-keeps-the-rest
  (let [root (scan-root! {"good" {:kind :exec :label "Good" :exec ["x"]}})]
    (fs/create-dirs (fs/path root "garbage"))
    (spit (str (fs/path root "garbage" "app.edn")) "{:kind :exec :label \"oops\"")     ; truncated edn
    (fs/create-dirs (fs/path root "specless"))
    (spit (str (fs/path root "specless" "app.edn")) (pr-str {:kind :exec :label "NoExec"}))
    (let [c (loader/load-catalog [root] "fallback-launcher.svg")]
      (is (= [:good] (:order c)) "bad apps skipped loudly; the rest boot"))
    (fs/delete-tree root)))

(deftest scan-missing-root-is-an-empty-catalog
  (let [c (loader/load-catalog ["/nope/missing"] "fallback-launcher.svg")]
    (is (= [] (:order c)) "no app content can stop the session")))

(deftest scan-merges-roots-later-wins-position-stable
  (let [base  (scan-root! {"paint" {:kind :exec :label "Paint" :exec ["p"]}
                           "web"   {:kind :exec :label "Web"   :exec ["w"]}})
        extra (scan-root! {"paint" {:kind :exec :label "Paint v2" :exec ["p2"]}
                           "amp"   {:kind :exec :label "Amp"   :exec ["a"]}})]
    (let [c (loader/load-catalog [base extra] "fallback-launcher.svg")]
      (is (= [:amp :paint :web] (:order c)) "union, abc on id — an override keeps its position")
      (is (= "Paint v2" (get-in c [:by-id :paint :label])) "later root wins on :id")
      (is (= ["p2"] (get-in c [:by-id :paint :exec]))))
    (fs/delete-tree base)
    (fs/delete-tree extra)))

(deftest scan-missing-second-root-is-not-fatal
  (let [base (scan-root! {"paint" {:kind :exec :label "Paint" :exec ["p"]}})]
    (let [c (loader/load-catalog [base "/mnt/nope/apps"] "fallback-launcher.svg")]
      (is (= [:paint] (:order c)) "fresh storage = normal, baked apps unaffected"))
    (fs/delete-tree base)))


;; --- kinds: the scan validates + derives identity ---

(deftest scan-kinds-derive-class-and-validate
  (let [root (scan-root! {})]
    (fs/create-dirs (fs/path root "lib"))                     ; :link — the teacher case
    (spit (str (fs/path root "lib" "app.edn"))
          (pr-str {:kind :link :label "Library" :url "http://x.local/"}))
    (fs/create-dirs (fs/path root "board" "app"))             ; :web-app, entry present
    (spit (str (fs/path root "board" "app.edn"))
          (pr-str {:kind :web-app :label "Board" :entry "index.html" :port 8100}))
    (spit (str (fs/path root "board" "app" "index.html")) "<html/>")
    (fs/create-dirs (fs/path root "hole"))                    ; :web-app, entry MISSING -> skip
    (spit (str (fs/path root "hole" "app.edn"))
          (pr-str {:kind :web-app :label "Hole" :entry "index.html" :port 8101}))
    (fs/create-dirs (fs/path root "mystery"))                 ; unknown kind -> skip (fwd compat)
    (spit (str (fs/path root "mystery" "app.edn")) (pr-str {:kind :flatpak :label "M"}))
    (fs/create-dirs (fs/path root "typo"))                    ; relative argv[0] absent -> skip
    (spit (str (fs/path root "typo" "app.edn")) (pr-str {:kind :exec :label "T" :exec ["./nope"]}))
    (fs/create-dirs (fs/path root "payload"))                 ; relative argv[0] present -> in
    (spit (str (fs/path root "payload" "app.edn")) (pr-str {:kind :exec :label "P" :exec ["./run.sh"]}))
    (spit (str (fs/path root "payload" "run.sh")) "#!/bin/sh\n")
    (let [c (loader/load-catalog [root] "fallback-launcher.svg")]
      (is (= [:board :lib :payload] (:order c)) "broken/unknown skipped, valid kinds in")
      (is (= "ujima-lib"   (get-in c [:by-id :lib :class]))   "link derives its class")
      (is (= "ujima-board" (get-in c [:by-id :board :class])) "web-app derives its class")
      (is (= ["./run.sh"]  (get-in c [:by-id :payload :exec])) "exec stays as-authored"))
    (fs/delete-tree root)))
