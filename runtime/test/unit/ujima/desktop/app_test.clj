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
  {"paint" {:kind :exec :label "Paint" :exec ["tuxpaint" "--nolockfile"] :window {:class "TuxPaint.TuxPaint"}}
   "web"   {:kind :exec :label "Web"   :exec ["chromium"] :window {:class "ujima-web"}}
   "sky"   {:kind :exec :label "Sky"   :exec ["stellarium"] :window {:class "stellarium"}}
   ;; :system = no launcher tile, pinned in the dock instead. console pins only once a token
   ;; unhides it, so it ships hidden.
   "files"   {:kind :exec :label "Files"   :category :system :exec ["pcmanfm"] :window {:class "pcmanfm"}}
   "console" {:kind :exec :label "Console" :category :system :hidden true :exec ["sh" "run.sh"] :window {:class "ujima-console"}}
   ;; two tiles of one suite: one res_class between them, told apart by the res_name each launches under
   "docs"   {:kind :exec :label "Documents"    :exec ["oo" "--new:word"] :window {:class "OFFICE" :instance "ujima-docs"}}
   "sheets" {:kind :exec :label "Spreadsheets" :exec ["oo" "--new:cell"] :window {:class "OFFICE" :instance "ujima-sheets"}}
   })

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


(defn- win [ws & {:keys [focused? title floating? wtype con full? class instance]}]
  {:ws ws :focused? (boolean focused?) :title (or title ws) :full? (boolean full?)
   :floating? (boolean floating?) :wtype (or wtype "normal") :con (or con 1)
   :class class :instance instance})

(defn- node [w]
  (cond-> {:id (:con w) :window (+ 1000 (:con w)) :name (:title w)
           :focused (:focused? w) :window_type (:wtype w) :fullscreen_mode (if (:full? w) 1 0)}
    (or (:class w) (:instance w))
    (assoc :window_properties (cond-> {}
                                (:class w)    (assoc :class (:class w))
                                (:instance w) (assoc :instance (:instance w))))))

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
                i3/try-command!           (fn [& args] (swap! fx* conj (into [:cmd] args)))
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
           (act/app->runnable bins {:kind :link :url "http://x.local/" :window {:class "ujima-lib"}})))
    (is (= ["serve-web-app" "/apps/board/app" "index.html" "8100" "ujima-board"]
           (act/app->runnable bins {:kind :web-app :dir "/apps/board" :entry "index.html"
                                    :port 8100 :window {:class "ujima-board"}}))
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

(deftest fullscreen-is-detected-and-hides-the-bars-in-multi
  (setup! [(win "web" :focused? true :full? true)] "web" :scopes #{:web})
  (stubbed #(app/handle-event! {:type :tick}))
  (is (true? (:fullscreen (:current (snap)))) "detected on the entry")
  (is (true? (:bars-hidden? (snap)))          "multi hides the bars for a fullscreen window")

  (setup! [(win "web" :focused? true)] "web" :scopes #{:web})
  (stubbed #(app/handle-event! {:type :tick}))
  (is (false? (:fullscreen (:current (snap)))))
  (is (false? (:bars-hidden? (snap)))         "windowed -> bars shown"))


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


(deftest the-lock-surface-is-routed-to-the-lock-workspace
  ;; it maps wherever focus happened to be; unpinned, locking would switch to an empty ws
  (setup! [(win "1" :focused? true :con 9 :title "Eww - lockscreen")] "1")
  (stubbed #(app/handle-event! {:type :window/changed}))
  (is (= [[:cmd "[con_id=9]" "move" "container" "to" "workspace" "lock"]] (fx-of :cmd))))

(deftest the-lock-surface-on-its-own-workspace-is-left-alone
  (setup! [(win "lock" :con 9 :title "Eww - lockscreen")] "1")
  (stubbed #(app/handle-event! {:type :window/changed}))
  (is (= [] (fx-of :cmd)) "already on the lock workspace — idempotent"))


(deftest tiles-sharing-a-class-are-routed-apart-by-instance
  (setup! [(win "1" :focused? true :con 7 :class "OFFICE" :instance "ujima-sheets")]
          "1" :scopes #{:sheets})
  (stubbed #(app/handle-event! {:type :window/changed}))
  (is (= [[:cmd "[con_id=7]" "move" "container" "to" "workspace" "sheets"]] (fx-of :cmd))))

(deftest a-shared-class-with-an-unnamed-instance-stays-where-it-mapped
  (setup! [(win "1" :focused? true :con 7 :class "OFFICE" :instance "something-else")] "1")
  (stubbed #(app/handle-event! {:type :window/changed}))
  (is (= [] (fx-of :cmd)) "neither tile describes it — better put than on the wrong one"))


(deftest app-dialog-is-routed-by-class
  ;; an app's own dialog (e.g. Inkscape's startup dialog, which maps before its main window) must
  ;; land on the app's workspace too — skipping it strands the app on home (the original bug)
  (setup! [(win "1" :focused? true :con 7 :class "stellarium" :wtype "dialog")] "1" :scopes #{:sky})
  (stubbed #(app/handle-event! {:type :window/changed}))
  (is (= [[:cmd "[con_id=7]" "move" "container" "to" "workspace" "sky"]] (fx-of :cmd))
      "the app's own dialog routes to its workspace"))


;; --- verbs validate ---

(defn- mode-of [] (:mode (snap)))

(def ^:private gaps0 [[:cmd "gaps" "top" "all" "set" "0"] [:cmd "gaps" "bottom" "all" "set" "0"]])
(def ^:private gaps-back [[:cmd "gaps" "top" "all" "set" "48"] [:cmd "gaps" "bottom" "all" "set" "68"]])

(deftest solo-enter-cold-runs-and-fills-the-screen
  (setup! [] "1")
  (stubbed #(app/solo-app! :web))
  (is (= [[:switch "web"]] (fx-of :switch)) "switched to P")
  (is (= [[:spawn :web ["chromium"]]] (fx-of :spawn)) "launched P")
  (is (= gaps0 (fx-of :cmd)) "bar gaps dropped so P fills the screen — no i3 fullscreen")
  (is (= :solo (mode-of)) "projection reports solo")
  (is (true? (:bars-hidden? (snap))) "solo hides the bars"))

(deftest solo-enter-warm-switches-only
  (setup! [(win "web" :focused? true)] "1" :scopes #{:web})
  (stubbed #(app/solo-app! :web))
  (is (= [[:switch "web"]] (fx-of :switch)))
  (is (= [] (fx-of :spawn)) "P already up — no re-spawn")
  (is (= gaps0 (fx-of :cmd)) "still fills the screen"))

(deftest solo-does-not-i3-fullscreen
  ;; the dialog fix: no fullscreen, so a tiled chooser covers via tabbing instead of hiding
  (setup! [] "1")
  (stubbed #(app/solo-app! :web))
  (is (empty? (filter #(some #{"fullscreen"} %) (fx-of :cmd))) "never issues i3 fullscreen"))

(deftest solo-scope-death-relaunches-P
  (setup! [] "web" :scopes #{})
  (stubbed #(do (app/solo-app! :web)       ; enter (scope now up)
                (swap! world* update :scopes disj :web)   ; P died
                (reset! fx* [])
                (app/handle-event! {:type :scope/died :app-id :web})))
  (is (= [[:spawn :web ["chromium"]]] (fx-of :spawn)) "P brought back"))

(deftest solo-scope-death-of-another-app-is-ignored
  (setup! [] "web" :scopes #{:web})
  (stubbed #(do (app/solo-app! :web)
                (reset! fx* [])
                (app/handle-event! {:type :scope/died :app-id :paint})))   ; not P
  (is (= [] (fx-of :spawn)) "only P relaunches"))

(deftest solo-relaunch-is-rate-limited
  ;; two deaths back-to-back: the first relaunches, the second is gated (rescheduled, no spawn now)
  (setup! [] "web" :scopes #{})
  (stubbed
    #(with-redefs [app/relaunch-ms 999999]
       (app/solo-app! :web)
       (reset! app/relaunch* 0)                    ; first death relaunches
       (swap! world* update :scopes disj :web)
       (app/handle-event! {:type :scope/died :app-id :web})
       (reset! fx* [])
       (swap! world* update :scopes disj :web)      ; immediate second death
       (app/handle-event! {:type :scope/died :app-id :web})))
  (is (= [] (fx-of :spawn)) "second death within the interval does not respawn now"))

(deftest solo-refuses-the-multi-verbs
  ;; close is in this list on purpose: it is what Alt+F4 calls, and a held machine must not
  ;; be able to close its way out. The circle's close releases first — see close-through-a-hold
  (setup! [(win "web" :focused? true) (win "paint")] "web" :scopes #{:web :paint})
  (stubbed
    #(do (app/solo-app! :web)
         (reset! fx* [])
         (app/run! :paint)          ; open another app
         (app/close-focused!)       ; close
         (app/go-home!)             ; home
         (app/cycle! 1)             ; cycle
         (app/switch-to! :paint)))  ; switch
  (is (= :solo (mode-of)) "still held")
  (is (= [] (fx-of :spawn)) "no new app opens in solo")
  (is (= [] (fx-of :kill)) "no close in solo — the chord cannot break a hold")
  (is (= [] (filterv #(= [:switch "1"] %) (fx-of :switch))) "no go-home in solo"))

(deftest release-restores-gaps-and-returns-to-multi
  (setup! [(win "web" :focused? true :con 7)] "web" :scopes #{:web})
  (stubbed #(do (app/solo-app! :web)
                (reset! fx* [])
                (app/release!)))
  (is (= :multi (mode-of)))
  (is (= gaps-back (fx-of :cmd)) "bar gaps put back"))

(deftest solo-current-app-holds-the-focused-app
  (setup! [(win "web" :focused? true)] "web" :scopes #{:web})
  (stubbed #(app/solo-current-app!))                ; no id -> solo the focused app
  (is (= :solo (mode-of)))
  (is (= gaps0 (fx-of :cmd)) "fills the screen"))

(deftest solo-current-app-at-home-throws
  (setup! [(win "1" :focused? true :class "ujima-launcher")] "1")
  (stubbed #(is (thrown? clojure.lang.ExceptionInfo (app/solo-current-app!))))  ; nothing to solo
  (is (false? (app/solo?)) "did not enter solo"))


(deftest lock-switches-to-the-lock-workspace-and-fills-the-screen
  (setup! [] "1")
  (stubbed #(app/lock!))
  (is (= [[:switch "lock"]] (fx-of :switch)) "switched to the shell's lock workspace")
  (is (= [] (fx-of :sh)) "nothing is mapped or torn down — the surface is already there")
  (is (= [] (fx-of :spawn)) "nothing is launched — the lock is a surface, not a process")
  (is (= gaps0 (fx-of :cmd)) "fills the screen")
  (is (true?  (app/locked?)) "reports locked")
  (is (false? (app/solo?))   "locked is its own mode, not solo")
  (is (= :locked (mode-of))))

(deftest lock-remembers-the-focused-app-and-unlock-returns-to-it
  ;; locked over an open web app: unlock just switches BACK to it — the surface stays open
  (setup! [(win "web" :focused? true :con 7)] "web" :scopes #{:web})
  (stubbed #(do (app/lock!)                                   ; captures :web as app-to-focus
                (reset! fx* [])
                (app/unlock!)))
  (is (= :multi (mode-of)))
  (is (= [] (fx-of :stop)) "nothing to stop — the app underneath was never touched")
  (is (some #{[:switch "web"]} (fx-of :switch)) "returns to the remembered app"))

(deftest unlock-goes-home-when-the-remembered-app-is-gone
  (setup! [] "1")                                             ; locked from home -> app-to-focus nil
  (stubbed #(do (app/lock!)
                (reset! fx* [])
                (app/unlock!)))
  (is (= :multi (mode-of)))
  (is (some #{[:switch "1"]} (fx-of :switch)) "home when there is nothing to return to"))

(deftest unlock-leaves-only-a-lock
  (setup! [(win "web" :focused? true)] "1" :scopes #{:web})
  (stubbed #(do (app/solo-app! :web) (reset! fx* []) (app/unlock!)))
  (is (true? (app/solo?)) "unlock! is a no-op on a soloed machine"))

(deftest release-leaves-any-pin
  ;; ungated by design — the token stick's escape. The circle gates itself first
  (setup! [(win "web" :focused? true)] "1" :scopes #{:web})
  (stubbed #(do (app/solo-app! :web) (reset! fx* []) (app/release!)))
  (is (= :multi (mode-of)) "release! drops solo")
  (setup! [] "1")
  (stubbed #(do (app/lock!) (reset! fx* []) (app/release!)))
  (is (= :multi (mode-of)) "release! drops lock too"))


;; --- the hold's own verbs: what focus, release and close refuse ---

(deftest release-drops-the-hold-and-passes-a-free-machine-by
  (setup! [(win "web" :focused? true)] "1" :scopes #{:web})
  (stubbed #(do (app/solo-app! :web) (reset! fx* []) (app/release!)))
  (is (= :multi (mode-of)) "release! leaves the hold")

  (setup! [(win "web" :focused? true :con 7)] "web" :scopes #{:web})
  (stubbed #(app/release!))                                  ; never held
  (is (= [] (fx-of :cmd)) "a free machine is left alone"))

(deftest the-hold-verbs-refuse-a-locked-machine
  ;; focus bounces on its own; the circle's release and close bounce by calling the gate
  (doseq [[what verb] [["focus"   #(app/solo-app! :web)]
                       ["focus-0" #(app/solo-current-app!)]
                       ["gate"    #(app/refuse-when-locked!)]]]
    (setup! [(win "web")] "1" :scopes #{:web})
    (stubbed #(do (app/lock!)
                  (is (thrown? clojure.lang.ExceptionInfo (verb)) what)))
    (is (true? (app/locked?)) (str what " left the lock standing"))))

(deftest close-through-a-hold
  ;; what /api's app/close does: release, THEN close — the pair the circle sends as one
  ;; request, and the pair a chord cannot assemble because release! is not on the local tier
  (setup! [(win "web" :focused? true :con 7)] "web" :scopes #{:web})
  (stubbed #(do (app/solo-app! :web)
                (reset! fx* [])
                (app/release!)
                (app/close-focused!)))
  (is (= :multi (mode-of)) "the hold is gone")
  (is (= gaps-back (fx-of :cmd)) "bar gaps put back")
  (is (= [[:kill]] (fx-of :kill)) "and the app is closed"))

(deftest release-when-already-multi-does-nothing
  (setup! [(win "web" :focused? true :con 7)] "web" :scopes #{:web})
  (stubbed #(app/release!))            ; never held
  (is (= [] (fx-of :cmd)) "no gap change — a normal desktop is left alone"))


(deftest verbs-resolve-or-throw
  (setup! [] "1")
  (stubbed
    #(do (is (thrown? clojure.lang.ExceptionInfo (app/run! :nope)))
         (is (thrown? clojure.lang.ExceptionInfo (app/switch-to! :nope)))))
  (is (= {:mode :multi :running [] :catalog [] :current nil} (app/current-apps-state))))



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
