(ns ujima.desktop.app-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [lib.shell :as shell]
            [ujima.linux.i3 :as i3]
            [ujima.linux.systemd :as systemd]
            [ujima.desktop.app :as app]))


;; The actor loop with i3 + scopes stubbed. world* holds the tree + focused ws + the set of live
;; scopes; mutations land in fx*; converged snapshots in pushed*. Verbs and events route straight
;; into handle-event! via the i3/emit! stub.


(def ^:private catalog-apps               ; dir name = :id (the scanner's contract)
  {"paint" {:label "Paint" :exec ["tuxpaint" "--nolockfile"] :class "TuxPaint.TuxPaint"}
   "web"   {:label "Web"   :exec ["chromium"] :class "ujima-web"}
   "sky"   {:label "Sky"   :exec ["stellarium"] :mode :fullscreen :class "stellarium"}})

(defn- scan-root!
  "Materialize {dir-name spec} as a temp scan root: <root>/<dir>/app.edn per entry."
  [apps]
  (let [root (fs/create-temp-dir {:prefix "ujima-apps"})]
    (doseq [[id spec] apps]
      (fs/create-dirs (fs/path root id))
      (spit (str (fs/path root id "app.edn")) (pr-str spec)))
    (str root)))

(def ^:private test-catalog
  (let [root (scan-root! catalog-apps)
        c    (app/load-catalog root)]
    (fs/delete-tree root)
    c))


(def ^:private world*  (atom nil))    ; {:wins [...] :focused-ws "..." :scopes #{app-id}}
(def ^:private fx*     (atom []))
(def ^:private pushed* (atom []))


(defn- win [ws & {:keys [focused? title floating? wtype con full? class]}]
  {:ws ws :focused? (boolean focused?) :title (or title ws) :full? (boolean full?)
   :floating? (boolean floating?) :wtype (or wtype "normal") :con (or con 1) :class class})

(defn- node [w]
  (cond-> {:id (:con w) :window (+ 1000 (:con w)) :name (:title w)
           :focused (:focused? w) :window_type (:wtype w) :fullscreen_mode (if (:full? w) 1 0)}
    (:class w) (assoc :window_properties {:class (:class w)})))

(defn- tree [wins]
  {:type "root"
   :nodes (vec (for [[ws ws-wins] (group-by :ws wins)]
                 {:type "workspace" :name ws
                  :nodes          (mapv node (remove :floating? ws-wins))
                  :floating_nodes (mapv node (filter :floating? ws-wins))}))})

(defn- setup! [wins focused-ws & {:keys [scopes] :or {scopes #{}}}]
  (reset! world*  {:wins wins :focused-ws focused-ws :scopes scopes})
  (reset! fx*     [])
  (reset! pushed* [])
  (app/init! {:catalog          test-catalog
              :converge-targets [(fn [next _] (swap! pushed* conj next))]}))

(defn- stubbed [f]
  (with-redefs [i3/get-tree!          (fn [] (tree (:wins @world*)))
                i3/focused-workspace  (fn [] (:focused-ws @world*))
                i3/switch-workspace!  (fn [ws] (swap! world* assoc :focused-ws ws)
                                               (swap! fx* conj [:switch ws]))
                i3/kill-focused!      (fn [] (swap! fx* conj [:kill]))
                i3/command?           (fn [& args] (swap! fx* conj (into [:cmd] args)))
                i3/emit!              (fn [ev] (app/handle-event! ev))
                systemd/active?       (fn [id] (contains? (:scopes @world*) id))
                systemd/spawn-scoped! (fn [id exec] (swap! world* update :scopes conj id)
                                                    (swap! fx* conj [:spawn id (vec exec)]))
                systemd/stop!         (fn [id] (swap! world* update :scopes disj id)
                                              (swap! fx* conj [:stop id]))
                shell/sh              (fn [& args] (swap! fx* conj [:sh (vec (rest args))]))]
    (f)))

(defn- fx-of [k] (filterv #(= k (first %)) @fx*))
(defn- snap  [] (last @pushed*))
(defn- open-ids [] (mapv :id (:apps (snap))))
(defn- current-id [] (:id (:current (snap))))


;; --- catalog scan: dirs with app.edn, abc order, app-OS split (bad content never throws) ---

(deftest scan-ids-from-dir-names-in-abc-order
  (let [root (scan-root! {"zebra" {:label "Z" :exec ["z"]}
                          "alpha" {:label "A" :exec ["a"]}
                          "mango" {:label "M" :exec ["m"]}})]
    (fs/create-dirs (fs/path root "payload-only"))            ; no app.edn -> not an app
    (spit (str (fs/path root "stray.txt")) "not a dir")
    (let [c (app/load-catalog root)]
      (is (= [:alpha :mango :zebra] (:order c)) "dir name = id, abc = launcher order")
      (is (= "A" (get-in c [:by-id :alpha :label]))))
    (fs/delete-tree root)))

(deftest scan-skips-broken-apps-and-keeps-the-rest
  (let [root (scan-root! {"good" {:label "Good" :exec ["x"]}})]
    (fs/create-dirs (fs/path root "garbage"))
    (spit (str (fs/path root "garbage" "app.edn")) "{:label \"oops\"")     ; truncated edn
    (fs/create-dirs (fs/path root "specless"))
    (spit (str (fs/path root "specless" "app.edn")) (pr-str {:label "NoExec"}))
    (let [c (app/load-catalog root)]
      (is (= [:good] (:order c)) "bad apps skipped loudly; the rest boot"))
    (fs/delete-tree root)))

(deftest scan-missing-root-is-an-empty-catalog
  (let [c (app/load-catalog "/nope/missing")]
    (is (= [] (:order c)) "no app content can stop the session")))


;; --- run: scope-gated switch-then-launch ---

(deftest run-cold-switches-and-scopes
  (setup! [] "1")
  (stubbed #(app/run! :paint))
  (is (= [[:switch "paint"]] (fx-of :switch)))
  (is (= [[:spawn :paint ["tuxpaint" "--nolockfile"]]] (fx-of :spawn)))
  (is (= :paint (current-id)) "the topbar shows the app you're opening"))

(deftest run-warm-switches-only
  (setup! [(win "paint" :focused? true)] "1" :scopes #{:paint})
  (stubbed #(app/run! :paint))
  (is (= [[:switch "paint"]] (fx-of :switch)))
  (is (= [] (fx-of :spawn)) "scope already up — switch only, never re-spawn"))

(deftest run-throw-rescues-home
  (setup! [] "1")
  (stubbed
    #(with-redefs [systemd/spawn-scoped! (fn [& _] (throw (ex-info "no systemd-run" {})))]
       (app/run! :paint)))
  (is (= [[:switch "paint"] [:switch "1"]] (fx-of :switch)) "switch to it, then home on failure"))


;; --- switch ---

(deftest switch-goes-to-the-app-without-launching
  (setup! [(win "web")] "1" :scopes #{:web})
  (stubbed #(app/switch-to! :web))
  (is (= [[:switch "web"]] (fx-of :switch)))
  (is (= [] (fx-of :spawn))))


;; --- close: polite, ✕✕ force (1-3s AND same con), zombie reap ---

(deftest close-is-polite-then-arms
  (setup! [(win "paint" :focused? true)] "paint" :scopes #{:paint})
  (stubbed #(app/close-focused!))
  (is (= [[:kill]] (fx-of :kill)) "first ✕ = WM_DELETE")
  (is (= [] (fx-of :stop)) "no force yet"))

(deftest close-double-click-does-not-force
  ;; two instant ✕ (delta < force-lo-ms) is an accidental double-click, not an escalation
  (setup! [(win "paint" :focused? true :con 7)] "paint" :scopes #{:paint})
  (stubbed #(do (app/close-focused!) (app/close-focused!)))
  (is (= [] (fx-of :stop)) "no force on a fast double-click")
  (is (= [[:kill] [:kill]] (fx-of :kill)) "just a re-sent WM_DELETE"))

(deftest close-XX-in-window-force-kills
  (setup! [(win "paint" :focused? true :con 7)] "paint" :scopes #{:paint})
  (stubbed
    #(with-redefs [app/force-lo-ms 0]                    ; make any 2nd ✕ count as deliberate
       (app/close-focused!)
       (app/close-focused!)))
  (is (= [[:kill]] (fx-of :kill)) "one polite WM_DELETE")
  (is (= [[:stop :paint]] (fx-of :stop)) "the 2nd ✕ on the same window force-kills"))

(deftest close-XX-spared-when-a-dialog-took-focus
  ;; a save-prompt steals focus to a NEW con, so the 2nd ✕ isn't the same window -> no force
  (setup! [(win "paint" :focused? true :con 7)] "paint" :scopes #{:paint})
  (stubbed
    #(with-redefs [app/force-lo-ms 0]
       (app/close-focused!)                              ; ✕ con 7
       (swap! world* assoc :wins [(win "paint" :focused? true :con 9 :wtype "dialog")])
       (app/close-focused!)))                            ; ✕ hits the dialog (con 9), not 7
  (is (= [] (fx-of :stop)) "the save-prompt is protected")
  (is (= [[:kill] [:kill]] (fx-of :kill))))

(deftest close-empty-app-workspace-reaps-the-scope
  (setup! [] "paint" :scopes #{:paint})               ; scope alive, no window (zombie / launching)
  (stubbed #(app/close-focused!))
  (is (= [[:stop :paint]] (fx-of :stop))))

(deftest close-gated-at-home
  (setup! [] "1")
  (stubbed #(app/close-focused!))
  (is (= [] (fx-of :kill)))
  (is (= [] (fx-of :stop)) "the launcher is never ours to close"))


;; --- go home: con-id (instant) + scope-death (backstop) ---

(deftest close-last-window-stops-the-scope-and-goes-home
  ;; the ✕'d window closed and nothing's left -> ensure the app is really gone (a still-launching
  ;; process, e.g. Stellarium, would else re-map a stray fullscreen window) + home
  (setup! [(win "paint" :focused? true :con 7)] "paint" :scopes #{:paint})
  (stubbed
    #(do (app/close-focused!)                            ; records con 7, WM_DELETE
         (swap! world* assoc :wins [])                   ; the window closed
         (reset! fx* [])
         (app/handle-event! {:type :window/close :con-id 7})))
  (is (= [[:stop :paint]] (fx-of :stop)) "scope stopped so it can't reopen")
  (is (= [[:switch "1"]] (fx-of :switch)) "and home"))

(deftest close-one-of-several-windows-keeps-the-app
  (setup! [(win "paint" :focused? true :con 7) (win "paint" :con 8)] "paint" :scopes #{:paint})
  (stubbed
    #(do (app/close-focused!)                            ; ✕ con 7
         (swap! world* assoc :wins [(win "paint" :con 8)])  ; con 7 gone, con 8 remains
         (reset! fx* [])
         (app/handle-event! {:type :window/close :con-id 7})))
  (is (= [] (fx-of :stop)) "another window remains — the app stays")
  (is (= [] (fx-of :switch)) "no go-home"))

(deftest unrelated-window-close-does-not-go-home
  (setup! [(win "paint" :focused? true :con 7)] "paint" :scopes #{:paint})
  (stubbed
    #(do (app/close-focused!)                            ; records con 7
         (reset! fx* [])
         (app/handle-event! {:type :window/close :con-id 99})))  ; some other window
  (is (= [] (fx-of :switch)) "not the con we asked to close — stay (this is the replace case)"))

(deftest scope-death-goes-home-when-showing-it
  (setup! [] "paint")                                    ; app self-quit: ws empty, focused there
  (stubbed #(app/handle-event! {:type :scope/died :app-id :paint}))
  (is (= [[:switch "1"]] (fx-of :switch))))

(deftest scope-death-elsewhere-is-ignored
  (setup! [(win "web" :focused? true)] "web" :scopes #{:web})
  (stubbed #(app/handle-event! {:type :scope/died :app-id :paint}))
  (is (= [] (fx-of :switch)) "a background app dying doesn't move you"))


;; --- open-url: cold scoped / warm messenger ---

(deftest open-url-cold-scopes-with-the-url
  (setup! [] "1")
  (stubbed #(app/open-url! "https://x.org"))
  (is (= [[:spawn :web ["chromium" "https://x.org"]]] (fx-of :spawn)))
  (is (= [[:switch "web"]] (fx-of :switch))))

(deftest open-url-warm-joins-via-messenger
  (setup! [(win "web" :focused? true)] "paint" :scopes #{:web})
  (stubbed #(app/open-url! "https://x.org"))
  (is (= [[:sh ["chromium" "https://x.org"]]] (fx-of :sh)) "plain messenger, no new scope")
  (is (= [] (fx-of :spawn)))
  (is (= [[:switch "web"]] (fx-of :switch))))

(deftest open-url-rejects-non-http
  (setup! [] "1")
  (stubbed #(is (thrown? clojure.lang.ExceptionInfo (app/open-url! "ftp://nope")))))


;; --- projection (tree = display), fullscreen hint, floaters ---

(deftest a-plain-tick-only-projects
  (setup! [(win "web" :focused? true)] "web" :scopes #{:web})
  (stubbed #(app/handle-event! {:type :window/change}))
  (is (= [] @fx*) "no world mutations")
  (is (= :web (current-id)))
  (is (= [:web] (open-ids))))

(deftest fullscreen-declared-or-detected
  (setup! [(win "sky" :focused? true)] "sky" :scopes #{:sky})            ; :sky declares :mode
  (stubbed #(app/handle-event! {:type :tick}))
  (is (true? (:fullscreen (:current (snap)))) "declared hint")

  (setup! [(win "web" :focused? true :full? true)] "web" :scopes #{:web}) ; detected from window
  (stubbed #(app/handle-event! {:type :tick}))
  (is (true? (:fullscreen (:current (snap)))) "detected fullscreen window")

  (setup! [(win "web" :focused? true)] "web" :scopes #{:web})             ; neither
  (stubbed #(app/handle-event! {:type :tick}))
  (is (false? (:fullscreen (:current (snap))))))

(deftest floating-app-window-gets-tiled
  (setup! [(win "web" :focused? true :floating? true :con 7)] "web" :scopes #{:web})
  (stubbed #(app/handle-event! {:type :window/change}))
  (is (= [[:cmd "[con_id=7]" "floating" "disable"]] (fx-of :cmd))))

(deftest tiled-windows-and-dialogs-left-alone
  (setup! [(win "web" :focused? true :con 9)
           (win "web" :floating? true :wtype "dialog" :con 10)] "web" :scopes #{:web})
  (stubbed #(app/handle-event! {:type :window/change}))
  (is (= [] (fx-of :cmd))))


;; --- route: an orphan window lands on its app's workspace by class (no focus steal) ---

(deftest orphan-window-routed-to-its-workspace-by-class
  ;; the window mapped on home because focus moved during launch -> move it to its app ws by WM_CLASS
  (setup! [(win "1" :focused? true :con 7 :class "stellarium")] "1" :scopes #{:sky})
  (stubbed #(app/handle-event! {:type :window/change}))
  (is (= [[:cmd "[con_id=7]" "move" "container" "to" "workspace" "sky"]] (fx-of :cmd))))

(deftest window-on-its-own-workspace-is-not-moved
  (setup! [(win "sky" :focused? true :con 7 :class "stellarium")] "sky" :scopes #{:sky})
  (stubbed #(app/handle-event! {:type :window/change}))
  (is (= [] (fx-of :cmd)) "already on its workspace — idempotent"))

(deftest app-dialog-is-routed-by-class
  ;; an app's own dialog (e.g. Inkscape's startup dialog, which maps before its main window) must
  ;; land on the app's workspace too — skipping it strands the app on home (the original bug)
  (setup! [(win "1" :focused? true :con 7 :class "stellarium" :wtype "dialog")] "1" :scopes #{:sky})
  (stubbed #(app/handle-event! {:type :window/change}))
  (is (= [[:cmd "[con_id=7]" "move" "container" "to" "workspace" "sky"]] (fx-of :cmd))
      "the app's own dialog routes to its workspace"))


;; --- verbs validate ---

(deftest verbs-resolve-or-throw
  (setup! [] "1")
  (stubbed
    #(do (is (thrown? clojure.lang.ExceptionInfo (app/run! :nope)))
         (is (thrown? clojure.lang.ExceptionInfo (app/switch-to! :nope)))))
  (is (= {:apps [] :current nil} (app/current-apps-state))))
