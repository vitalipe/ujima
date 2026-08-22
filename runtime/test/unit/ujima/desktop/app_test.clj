(ns ujima.desktop.app-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [lib.shell :as shell]
            [ujima.linux.i3 :as i3]
            [ujima.linux.systemd :as systemd]
            [ujima.desktop.app :as app]
            [ujima.desktop.app.act :as act]
            [ujima.desktop.app.catalog :as catalog]
            [ujima.desktop.app.catalog.loader :as loader]))


;; The pass with i3 + scopes stubbed. world* holds the tree + focused ws + the set of live
;; scopes; mutations land in fx*; converged snapshots in pushed*.


(def ^:private catalog-apps               ; dir name = :id (the scanner's contract)
  {"paint" {:kind :exec :label "Paint" :exec ["tuxpaint" "--nolockfile"] :class "TuxPaint.TuxPaint"}
   "web"   {:kind :exec :label "Web"   :exec ["chromium"] :class "ujima-web"}
   "sky"   {:kind :exec :label "Sky"   :exec ["stellarium"] :mode :fullscreen :class "stellarium"}
   ;; :system = no launcher tile, pinned in the dock instead. console pins only once a token
   ;; unhides it, so it ships hidden.
   "files"   {:kind :exec :label "Files"   :category :system :exec ["pcmanfm"] :class "pcmanfm"}
   "console" {:kind :exec :label "Console" :category :system :hidden true :exec ["sh" "run.sh"] :class "ujima-console"}})

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
        c    (loader/load-catalog [root] "fallback-launcher.svg")]
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
                systemd/active?       (fn [id] (contains? (:scopes @world*) id))
                systemd/spawn-scoped! (fn [id exec _dir opts]
                                        (swap! world* update :scopes conj id)
                                        (swap! fx* conj [:spawn id (vec exec)])
                                        (when opts (swap! fx* conj [:spawn-opts id opts])))
                systemd/stop!         (fn [id] (swap! world* update :scopes disj id)
                                              (swap! fx* conj [:stop id]))
                shell/sh              (fn [& args] (swap! fx* conj [:sh (vec (rest args))]))]
    (f)))

(defn- fx-of [k] (filterv #(= k (first %)) @fx*))
(defn- snap  [] (last @pushed*))
(defn- open-ids [] (mapv :id (:running (snap))))
(defn- current-id [] (:id (:current (snap))))


;; --- kinds: app->runnable computes argv at spawn ---

(deftest app->runnable-computes-argv-per-kind
  (let [bins {:open-web-app-bin "open-web-app" :serve-web-app-bin "serve-web-app"}]
    (is (= ["tuxpaint" "--nolockfile"]
           (act/app->runnable bins {:kind :exec :exec ["tuxpaint" "--nolockfile"] :dir "/x"})))
    (is (= ["open-web-app" "http://x.local/" "ujima-lib"]
           (act/app->runnable bins {:kind :link :url "http://x.local/" :class "ujima-lib"})))
    (is (= ["serve-web-app" "/apps/board/app" "index.html" "8100" "ujima-board"]
           (act/app->runnable bins {:kind :web-app :dir "/apps/board" :entry "index.html"
                                    :port 8100 :class "ujima-board"}))
        "port coerced to string, serve dir = <dir>/app")))


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
    #(with-redefs [act/force-lo-ms 0]                    ; make any 2nd ✕ count as deliberate
       (app/close-focused!)
       (app/close-focused!)))
  (is (= [[:kill]] (fx-of :kill)) "one polite WM_DELETE")
  (is (= [[:stop :paint]] (fx-of :stop)) "the 2nd ✕ on the same window force-kills"))

(deftest close-XX-spared-when-a-dialog-took-focus
  ;; a save-prompt steals focus to a NEW con, so the 2nd ✕ isn't the same window -> no force
  (setup! [(win "paint" :focused? true :con 7)] "paint" :scopes #{:paint})
  (stubbed
    #(with-redefs [act/force-lo-ms 0]
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
         (app/handle-event! {:type :window/closed :con-id 7})))
  (is (= [[:stop :paint]] (fx-of :stop)) "scope stopped so it can't reopen")
  (is (= [[:switch "1"]] (fx-of :switch)) "and home"))

(deftest close-one-of-several-windows-keeps-the-app
  (setup! [(win "paint" :focused? true :con 7) (win "paint" :con 8)] "paint" :scopes #{:paint})
  (stubbed
    #(do (app/close-focused!)                            ; ✕ con 7
         (swap! world* assoc :wins [(win "paint" :con 8)])  ; con 7 gone, con 8 remains
         (reset! fx* [])
         (app/handle-event! {:type :window/closed :con-id 7})))
  (is (= [] (fx-of :stop)) "another window remains — the app stays")
  (is (= [] (fx-of :switch)) "no go-home"))

(deftest unrelated-window-close-does-not-go-home
  (setup! [(win "paint" :focused? true :con 7)] "paint" :scopes #{:paint})
  (stubbed
    #(do (app/close-focused!)                            ; records con 7
         (reset! fx* [])
         (app/handle-event! {:type :window/closed :con-id 99})))  ; some other window
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
  (stubbed #(app/handle-event! {:type :window/changed}))
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
  (stubbed #(app/handle-event! {:type :window/changed}))
  (is (= [[:cmd "[con_id=7]" "floating" "disable"]] (fx-of :cmd))))

(deftest tiled-windows-and-dialogs-left-alone
  (setup! [(win "web" :focused? true :con 9)
           (win "web" :floating? true :wtype "dialog" :con 10)] "web" :scopes #{:web})
  (stubbed #(app/handle-event! {:type :window/changed}))
  (is (= [] (fx-of :cmd))))


;; --- route: an orphan window lands on its app's workspace by class (no focus steal) ---

(deftest orphan-window-routed-to-its-workspace-by-class
  ;; the window mapped on home because focus moved during launch -> move it to its app ws by WM_CLASS
  (setup! [(win "1" :focused? true :con 7 :class "stellarium")] "1" :scopes #{:sky})
  (stubbed #(app/handle-event! {:type :window/changed}))
  (is (= [[:cmd "[con_id=7]" "move" "container" "to" "workspace" "sky"]] (fx-of :cmd))))

(deftest window-on-its-own-workspace-is-not-moved
  (setup! [(win "sky" :focused? true :con 7 :class "stellarium")] "sky" :scopes #{:sky})
  (stubbed #(app/handle-event! {:type :window/changed}))
  (is (= [] (fx-of :cmd)) "already on its workspace — idempotent"))

(deftest the-launcher-is-routed-home
  ;; an app opened before the shell is up (a token stick at boot) maps the launcher on the app's
  ;; workspace, leaving home empty
  (setup! [(win "sky" :focused? true :con 9 :class "ujima-launcher")] "sky" :scopes #{:sky})
  (stubbed #(app/handle-event! {:type :window/changed}))
  (is (= [[:cmd "[con_id=9]" "move" "container" "to" "workspace" "1"]] (fx-of :cmd))))


(deftest the-launcher-at-home-is-left-alone
  (setup! [(win "1" :focused? true :con 9 :class "ujima-launcher")] "1")
  (stubbed #(app/handle-event! {:type :window/changed}))
  (is (= [] (fx-of :cmd)) "already home — idempotent"))


(deftest app-dialog-is-routed-by-class
  ;; an app's own dialog (e.g. Inkscape's startup dialog, which maps before its main window) must
  ;; land on the app's workspace too — skipping it strands the app on home (the original bug)
  (setup! [(win "1" :focused? true :con 7 :class "stellarium" :wtype "dialog")] "1" :scopes #{:sky})
  (stubbed #(app/handle-event! {:type :window/changed}))
  (is (= [[:cmd "[con_id=7]" "move" "container" "to" "workspace" "sky"]] (fx-of :cmd))
      "the app's own dialog routes to its workspace"))


;; --- verbs validate ---

(deftest verbs-resolve-or-throw
  (setup! [] "1")
  (stubbed
    #(do (is (thrown? clojure.lang.ExceptionInfo (app/run! :nope)))
         (is (thrown? clojure.lang.ExceptionInfo (app/switch-to! :nope)))))
  (is (= {:running [] :catalog [] :current nil} (app/current-apps-state))))



;; --- per-app env: set out of band, applied to every launch ---

(deftest an-app-env-reaches-its-launches-and-only-its-own
  (setup! [] "1")
  (stubbed #(do (catalog/merge-app! :paint {:env {"UJIMA_CIRCLE_TOKEN" "deadbeef"}})
                (app/run! :paint)))
  (is (= [[:spawn-opts :paint {:extra-env {"UJIMA_CIRCLE_TOKEN" "deadbeef"}}]] (fx-of :spawn-opts)))

  (setup! [] "1")
  (stubbed #(app/run! :web))
  (is (empty? (fx-of :spawn-opts)) "another app gets nothing"))


(deftest an-app-env-survives-a-relaunch
  ;; closed and reopened from the bar is the same launch path — it must still be handed the env
  (setup! [] "1")
  (stubbed #(do (catalog/merge-app! :paint {:env {"T" "1"}})
                (app/run! :paint)))
  (reset! world* {:wins [] :focused-ws "1" :scopes #{}})   ; the scope is gone, the env is not
  (reset! fx* [])
  (stubbed #(app/run! :paint))
  (is (= [[:spawn-opts :paint {:extra-env {"T" "1"}}]] (fx-of :spawn-opts))))


(deftest clearing-an-app-env-stops-it-reaching-the-spawn
  (setup! [] "1")
  (stubbed #(do (catalog/merge-app! :paint {:env {"T" "1"}})
                (catalog/merge-app! :paint {:env nil})
                (app/run! :paint)))
  (is (empty? (fx-of :spawn-opts)) "no env = no opts at all, not an empty :extra-env"))


;; --- runtime entry changes ---

(deftest an-entry-change-is-visible-at-once
  (setup! [] "1")
  (catalog/merge-app! :console {:hidden false})
  (is (false? (:hidden (first (filter #(= :console (:id %)) (app/catalog-listing)))))
      "synchronous: a launch right after it sees the change"))


(deftest changes-to-different-fields-do-not-clobber-each-other
  (setup! [] "1")
  (stubbed #(do (catalog/merge-app! :console {:env {"T" "1"}})
                (catalog/merge-app! :console {:hidden false})))
  (stubbed #(app/run! :console))
  (is (= [[:spawn-opts :console {:extra-env {"T" "1"}}]] (fx-of :spawn-opts))
      "the second change carried only :hidden — it must not drop the env the first one set"))


(deftest an-unknown-app-cannot-be-changed
  (setup! [] "1")
  (is (thrown? Exception (catalog/merge-app! :nope {:hidden false}))))
