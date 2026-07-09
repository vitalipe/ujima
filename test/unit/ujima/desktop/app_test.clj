(ns ujima.desktop.app-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [lib.shell :as shell]
            [ujima.linux.i3 :as i3]
            [ujima.desktop.app :as app]))


;; The actor's decision loop only (no i3, no processes): the tree, the focused
;; workspace and every world mutation are stubbed; stubs record what they were
;; asked to do. handle-event! is driven directly — queue order is the caller's.


(def ^:private catalog-edn
  {:apps [{:id :paint :label "Paint" :exec ["tuxpaint"] :class "TuxPaint"}
          {:id :books :label "Books" :exec ["chromium" "--class=ujima-books"] :class "ujima-books"}]})


(defn- win
  [con-id class ws & {:keys [focused? floating? transient? title fullscreen?]}]
  {:con-id con-id :class class :ws ws :focused? focused?
   :floating? floating? :transient? transient? :title (or title class) :fullscreen? fullscreen?})


(defn- win-node [{:keys [con-id class title focused? transient? fullscreen?]}]
  {:id con-id :window (+ 1000 con-id) :name title :focused (boolean focused?)
   :fullscreen_mode (if fullscreen? 1 0)
   :window_properties (cond-> {:class class}
                        transient? (assoc :transient_for 1))})


(defn- tree [wins]
  {:type "root"
   :nodes (vec (for [[ws ws-wins] (group-by :ws wins)]
                 {:type "workspace" :name ws
                  :nodes          (mapv win-node (remove :floating? ws-wins))
                  :floating_nodes (mapv win-node (filter :floating? ws-wins))}))})


(def ^:private world*  (atom nil))  ; {:wins [...] :focused-ws "..."}
(def ^:private fx*     (atom []))
(def ^:private pushed* (atom []))


(defn- setup! [wins focused-ws]
  (let [f (str (fs/create-temp-file {:prefix "apps" :suffix ".edn"}))]
    (fs/delete-on-exit f)
    (spit f (pr-str catalog-edn))
    (app/load-catalog! f))                        ; also resets the intent + spawn ledgers
  (app/set-push! #(swap! pushed* conj %))
  (app/set-bars! nil)                            ; each test wires its own bar stub (or none)
  (reset! world* {:wins wins :focused-ws focused-ws})
  (reset! fx* [])
  (reset! pushed* []))


(defn- stubbed
  "Run F with the world mocked: reads come from world*, mutations land in fx*
   (a workspace switch also moves world's focus, like the real one)."
  [f]
  (with-redefs [i3/get-tree!         (fn [] (tree (:wins @world*)))
                i3/focused-workspace (fn [] (:focused-ws @world*))
                i3/switch-workspace! (fn [ws] (swap! world* assoc :focused-ws ws)
                                              (swap! fx* conj [:switch ws]))
                i3/place!            (fn [con ws] (swap! fx* conj [:place con ws]))
                i3/kill-con!         (fn [con] (swap! fx* conj [:kill con]))
                i3/emit-in!          (fn [_ms {:keys [type] :as ev}]   ; the recheck echoes
                                       (case type
                                         :recheck/proc   (swap! fx* conj [:hint-proc (:app-id ev) (:at ev)])
                                         :recheck/window (swap! fx* conj [:hint-window (:con-id ev) (:at ev)])))
                shell/sh             (fn [& args] (swap! fx* conj [:spawn (vec (rest args))])
                                                  {:proc nil})]
    (f)))


(defn- fx-of [kind] (filterv #(= kind (first %)) @fx*))

(defn- state-of [id]
  (->> @pushed* last :apps (some #(when (= id (:id %)) (:state %)))))

(def ^:private run-paint
  {:type :app/run :app {:id :paint :exec ["tuxpaint"] :class "TuxPaint"}})


(deftest run-spawns-a-closed-app-onto-staging
  (setup! [] "1")
  (stubbed #(app/handle-event! run-paint))
  (is (= [[:switch "ujima-loading"]] (fx-of :switch)) "spawn maps on staging, not on the launcher")
  (is (= [[:spawn ["tuxpaint"]]] (fx-of :spawn)))
  (is (= 1 (count (fx-of :hint-proc))) "the never-windowed recheck is armed")
  (is (= :new (state-of :paint))))


(deftest run-rescues-home-when-the-spawn-throws
  ;; an absent binary throws at spawn: no registry entry exists for the recheck
  ;; to expire, so the rescue must happen inline — and a later run may retry
  (setup! [] "1")
  (stubbed
    #(with-redefs [shell/sh (fn [& _] (throw (ex-info "No such file" {})))]
       (app/handle-event! run-paint)))
  (is (= [[:switch "ujima-loading"] [:switch "1"]] (fx-of :switch)) "staging, then straight home")
  (is (= [] (fx-of :hint-proc)) "nothing to recheck")
  (is (nil? (state-of :paint)) "still :closed — gone from the snapshot")
  (stubbed #(app/handle-event! run-paint))
  (is (= 1 (count (fx-of :spawn))) "the retry spawns"))


(deftest run-gates-while-still-opening
  (setup! [] "1")
  (stubbed
    #(do (app/handle-event! run-paint)
         (reset! fx* [])
         (app/handle-event! run-paint)))
  (is (= [] @fx*) "no second spawn, no switch — the recheck owns a stuck spawn")
  (is (= :new (state-of :paint))))


(deftest run-focuses-a-running-app
  (setup! [(win 7 "TuxPaint" "paint" :focused? true)] "paint")
  (stubbed #(app/handle-event! run-paint))
  (is (= [[:switch "paint"]] (fx-of :switch)) "run on a running app = focus its workspace")
  (is (= [] (fx-of :spawn)))
  (is (= :running (state-of :paint))))


(deftest recheck-proc-expires-a-spawn-that-never-windowed
  (setup! [] "1")
  (stubbed
    #(do (app/handle-event! run-paint)
         (let [[[_ id at]] (fx-of :hint-proc)]
           (reset! fx* [])
           (app/handle-event! {:type :recheck/proc :app-id id :at at}))))
  (is (= [[:switch "1"]] (fx-of :switch)) "the user is rescued off staging")
  (is (nil? (state-of :paint)) "back to :closed — gone from the snapshot"))


(deftest recheck-proc-is-stale-safe-and-window-safe
  ;; wrong :at (a newer spawn owns the entry) touches nothing; once the window
  ;; arrives, the ORIGINAL recheck must be a no-op too
  (setup! [] "1")
  (stubbed
    #(do (app/handle-event! run-paint)
         (let [[[_ id at]] (fx-of :hint-proc)]
           (reset! fx* [])
           (app/handle-event! {:type :recheck/proc :app-id id :at (inc at)})
           (is (= :new (state-of :paint)) "stale recheck touches nothing")
           (swap! world* assoc :wins [(win 7 "TuxPaint" "paint" :focused? true)])
           (app/handle-event! {:type :recheck/proc :app-id id :at at}))))
  (is (= :running (state-of :paint)) "the look marks it windowed before the expiry judges")
  (is (= [] (fx-of :switch))))


(deftest close-kills-focused-gates-repeat-and-expires-the-intent
  (setup! [(win 7 "TuxPaint" "paint" :focused? true)] "paint")
  (stubbed
    #(do (app/handle-event! {:type :app/close-focused})
         (app/handle-event! {:type :app/close-focused})
         (is (= [[:kill 7]] (fx-of :kill)) "one WM_close while the intent is pending")
         (let [[[_ con at]] (fx-of :hint-window)]
           (app/handle-event! {:type :recheck/window :con-id con :at at})  ; quit-confirm held it
           (app/handle-event! {:type :app/close-focused}))))
  (is (= [[:kill 7] [:kill 7]] (fx-of :kill)) "an expired intent frees a re-close"))


(deftest close-gates-without-a-managed-focus
  (setup! [(win 8 "eww" "1" :focused? true)] "1")
  (stubbed #(app/handle-event! {:type :app/close-focused}))
  (is (= [] (fx-of :kill)) "the launcher (uncataloged class) is not ours to close"))


(deftest placement-moves-strays-and-unfloats-the-launcher
  (setup! [(win 7 "TuxPaint" "write" :focused? true)                       ; on another app's workspace
           (win 8 "eww" "books" :floating? true)                           ; the launcher, floating wherever
           (win 9 "TuxPaint" "paint" :transient? true :floating? true)]    ; a dialog floats in peace
          "write")
  (stubbed #(app/handle-event! {:type :window/focus :con-id 7}))
  (is (= #{[:place 7 "paint"] [:place 8 "1"]} (set (fx-of :place)))
      "the stray and the launcher move; the dialog is left alone"))


(deftest rescue-leaves-live-and-transitional-workspaces-alone
  ;; a DEAD focused workspace (zero windows) -> home; a workspace with any
  ;; window — even an unmanaged focus mid-handoff — is not stranded
  (setup! [(win 7 "TuxPaint" "paint")] "books")
  (stubbed #(app/handle-event! {:type :window/focus :con-id 99}))
  (is (= [[:switch "1"]] (fx-of :switch)) "empty focused workspace -> home")

  (setup! [(win 7 "TuxPaint" "paint") (win 8 "SomethingElse" "paint")] "paint")
  (stubbed #(app/handle-event! {:type :window/focus :con-id 8}))
  (is (= [] (fx-of :switch)) "windows live here — nothing to rescue"))


(deftest bars-latch-hidden-through-a-fullscreen-apps-flapping
  ;; hide once when the focused app goes fullscreen; STAY hidden through the app's own
  ;; fullscreen flapping (SDL games toggle it — reopening would perturb the app into a
  ;; feedback loop); reopen only when focus leaves the app (eww launcher = uncataloged = nobody)
  (setup! [(win 7 "TuxPaint" "paint" :focused? true :fullscreen? true)] "paint")
  (let [bars (atom [])]
    (app/set-bars! #(swap! bars conj %))
    (stubbed #(app/handle-event! {:type :window/fullscreen :con-id 7}))
    (is (= [false] @bars) "focused fullscreen -> hidden once")
    (swap! world* assoc :wins [(win 7 "TuxPaint" "paint" :focused? true)])       ; same app flaps to windowed
    (stubbed #(app/handle-event! {:type :window/fullscreen :con-id 7}))
    (is (= [false] @bars) "same app, transient windowed -> still hidden (latched)")
    (reset! world* {:wins [(win 8 "eww" "1" :focused? true)] :focused-ws "1"})    ; focus leaves to the launcher
    (stubbed #(app/handle-event! {:type :window/focus :con-id 8}))
    (is (= [false true] @bars) "focus left the app -> bars shown")))


(deftest run-resolves-in-the-catalog-or-throws
  (setup! [] "1")
  (let [emitted (atom nil)]
    (with-redefs [i3/emit! (fn [ev] (reset! emitted ev))]
      (app/run! :paint)
      (is (= :app/run (:type @emitted)))
      (is (= ["tuxpaint"] (get-in @emitted [:app :exec])) "the catalog's full app map rides the event")
      (is (thrown? clojure.lang.ExceptionInfo (app/run! :nope))))))
