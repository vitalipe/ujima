(ns ujima.desktop.app
  "The app layer. The WORKSPACE is an app's identity (app :write lives on workspace \"write\",
   home is \"1\"), so windows are never matched on WM_CLASS; each launch lands in a systemd
   scope (alive? + kill), and i3 owns placement and focus.

   The session is in one of three MODES — Multi (the free desktop), Solo (one app), Locked
   (the shell's lock surface on its own workspace, over a remembered app); release! returns to
   Multi from either pin."
  (:require [ujima.linux.i3 :as i3]
            [ujima.desktop.app.catalog    :as catalog]
            [ujima.desktop.app.act        :as act]
            [ujima.desktop.app.projection :as proj :refer [home-ws]]))


(defonce ^:private prev*     (atom nil))   ; last snapshot, the prv of (next prv)
(defonce ^:private targets*  (atom []))
(defonce ^:private lock*     (Object.))    ; every pass holds this
(defonce ^:private state*    (atom nil))   ; the current Mode: a Multi, Solo, or Locked record
(defonce ^:private relaunch* (atom 0))     ; last solo relaunch, ms — the crash-loop gate


(def ^:private browser-app :web)
(def ^:private relaunch-ms 3000)           ; a crashing P relaunches no faster than this


(declare handle-event! ->Multi)


(defn init! [{:keys [catalog] :as cfg}]
  (catalog/init! catalog)
  (reset! prev*     nil)
  (reset! state*    (->Multi))
  (reset! relaunch* 0)
  (reset! targets*  [])
  (act/init! (select-keys cfg [:open-web-app-bin :serve-web-app-bin]))
  catalog)


(defn on-converge!
  "Attach a target: F gets (snapshot, previous) after every pass."
  [f]
  (swap! targets* conj f)
  nil)


(defn catalog-listing [] (catalog/listing (catalog/current)))

(defn icon-path
  "The catalog-resolved icon path for ID; nil for an unknown app."
  [id]
  (get-in (catalog/current) [:by-id id :icon]))


(defn- observe! []
  {:focused-ws (i3/focused-workspace)
   :ws->wins   (group-by :workspace (i3/window-facts (i3/get-tree!)))
   :catalog    (catalog/current)})


(defn- converge! [snapshot]
  (let [prv @prev*]
    (reset! prev* snapshot)
    (doseq [t @targets*] (t snapshot prv))))


(defn- dispatch
  "HANDLERS' entry for EV, if any — absent = refused. A handler that needs the tree observes
   it itself, so a command that doesn't decide costs no observe."
  [handlers ev]
  (when-let [f (handlers (:type ev))] (f ev)))


(defn- settle!
  "Shared world-maintenance after a mode acts: re-observe, un-float stray app windows, route
   orphans to their workspace. Returns that world for project!."
  []
  (let [w (observe!)]
    (act/settle-floaters! w)
    (act/route-windows! w)
    w))


(defn- relaunch!
  "Bring P back, no faster than relaunch-ms — a fast-crashing P reschedules (re-emit :scope/died)
   instead of spinning. The re-emit re-dispatches on the mode current THEN, so exiting solo
   mid-wait just goes home."
  [app]
  (let [now (System/currentTimeMillis) elapsed (- now @relaunch*)]
    (if (>= elapsed relaunch-ms)
      (do (reset! relaunch* now)
          (act/run! (catalog/resolve! app) [])
          (act/fill-screen!))
      (future (Thread/sleep (- relaunch-ms elapsed))
              (handle-event! {:type :scope/died :app-id app})))))


;; --- per-mode handler tables (absent = refused) + shared setup/projection helpers ---

(def ^:private multi-handlers
  {:app/run       (fn [ev] (act/run! (:app ev) (:extra ev [])))
   :app/switch    (fn [ev] (i3/switch-workspace! (name (:id (:app ev)))))
   :app/open-url  (fn [ev] (act/open-url! (:app ev) (:url ev)))
   :app/close     (fn [_]  (act/close! (observe!)))
   :app/home      (fn [_]  (i3/switch-workspace! home-ws))
   :app/cycle     (fn [ev] (act/cycle! (observe!) (:step ev)))
   :window/closed (fn [ev] (act/window-closed! (observe!) (:con-id ev)))
   :scope/died    (fn [ev] (act/go-home-if-empty! (observe!) (:app-id ev)))})


(defn- keep-alive-handlers
  "Closed over the pinned app P: relaunch it when (and only when) IT dies — :scope/died fires
   for any app, and a backgrounded app from an X->Y re-pin can die too."
  [app]
  {:scope/died (fn [ev] (when (= (:app-id ev) app) (relaunch! app)))})


(defn- pin!
  "The shared setup for solo and lock: run APP (switch-then-launch) and fill the screen."
  [app]
  (act/run! (catalog/resolve! app) [])
  (act/fill-screen!))


(defn- pin-project [world mode-kw]
  (assoc (proj/base world) :mode mode-kw :bars-hidden? true))


;; --- the three modes (see the ns doc). enter! RETURNS the mode to store, so it can enrich
;; --- itself as it enters — Locked captures the app to return to on exit.

(defprotocol Mode
  (enter!   [m]       "set up on entering; returns the mode to store (may enrich itself)")
  (exit!    [m]       "tear down on leaving")
  (act!     [m ev]    "run this mode's handler for EV, if it admits one")
  (project! [m world] "the base projection stamped with this mode's data"))


(defrecord Multi []
  Mode
  (enter!   [m]       m)
  (exit!    [_]       nil)
  (act!     [_ ev]    (dispatch multi-handlers ev))
  (project! [_ world] (let [snap (proj/base world)]
                        (assoc snap :mode :multi
                                    :bars-hidden? (boolean (get-in snap [:current :fullscreen]))))))


(defrecord Solo [app]
  Mode
  (enter!   [m]       (pin! app) m)
  (exit!    [_]       (act/restore-gaps!))
  (act!     [_ ev]    (dispatch (keep-alive-handlers app) ev))
  (project! [_ world] (pin-project world :solo)))


(defrecord Locked [app-to-focus]
  Mode
  (enter!   [m]       (let [w     (observe!) ; before the switch moves us
                            focus (proj/app-of-ws w (:focused-ws w))]

                        (act/show-lock!)
                        (assoc m :app-to-focus focus))) ; remember it for exit!

  (exit!    [m]       (act/hide-lock!)

                      (let [w    (observe!)
                            back (:app-to-focus m)]

                        (i3/switch-workspace!
                          (if (and back (seq (get (:ws->wins w) (name back))))
                            (name back)                            ; the app is still open
                            home-ws))))                            ; gone (or was home)

  (act!     [_ ev]    (dispatch {} ev))    ; no handlers: every verb refused
  (project! [_ world] (pin-project world :locked)))


(defn- event->mode [ev]
  (case (:type ev)
    :mode/solo   (->Solo (:id (:app ev)))
    :mode/multi  (->Multi)
    :mode/locked (->Locked nil)))


(defn handle-event!
  "One pass under the lock: a :mode/* event transitions (exit! old, then enter! the new and
   store what it returns — always paired); then the mode acts on EV, settle! reconciles, and
   the mode projects for converge."
  [ev]
  (locking lock*
    (when (= "mode" (namespace (:type ev)))
      (exit! @state*)
      (reset! state* (enter! (event->mode ev))))
    (act! @state* ev)
    (converge! (project! @state* (settle!)))))


;; --- verbs: validate, then one pass ---

(defn run!           [id] (handle-event! {:type :app/run    :app (catalog/resolve! id)}))
(defn switch-to!     [id] (handle-event! {:type :app/switch :app (catalog/resolve! id)}))
(defn go-home!       []   (handle-event! {:type :app/home}))
(defn cycle!         [step] (handle-event! {:type :app/cycle :step step}))


(defn solo?  [] (instance? Solo   @state*))
(defn locked? [] (instance? Locked @state*))

(defn refuse-when-locked!
  "Throw unless the machine is free of the lock. Public because the gate belongs to the CALLER:
   the circle may not release a lock, the token stick may."
  []
  (when (locked?) (throw (ex-info "the machine is locked" {:error :app/locked}))))


(defn solo-app!
  "Hold the machine to APP."
  [id]
  (refuse-when-locked!)
  (handle-event! {:type :mode/solo :app (catalog/resolve! id)}))


(defn solo-current-app!
  "Hold the machine to whatever it has focused right now; throws at home, with nothing to hold."
  []
  (let [w (observe!)]
    (if-let [id (proj/app-of-ws w (:focused-ws w))]
      (solo-app! id)
      (throw (ex-info "no app to solo" {:error :app/no-current-app})))))


(defn lock!   [] (handle-event! {:type :mode/locked}))
(defn unlock! [] (when (locked?) (handle-event! {:type :mode/multi})))   ; leaves ONLY lock


(defn release!
  "Go to Multi from wherever — solo OR lock — and a no-op when already there. UNGATED on
   purpose: this is both the circle's \"leave the hold\" and the token stick's escape from
   anything, and only the caller's gate tells those apart (see refuse-when-locked!)."
  []
  (when-not (instance? Multi @state*) (handle-event! {:type :mode/multi})))


(defn close-focused!
  "Close the focused app — refused in a pinned mode, like every other multi verb, because this
   is what the Alt+F4 chord calls. The circle's close lets go of the hold FIRST (see /api's
   app/close): that release is the circle's to make, never the held machine's."
  []
  (handle-event! {:type :app/close}))


(defn open-url! [url]
  (when-not (re-matches #"https?://\S+" (str url))
    (throw (ex-info "not an http url" {:error :app/bad-url :url (str url)})))
  (handle-event! {:type :app/open-url :app (catalog/resolve! browser-app) :url url}))


(defn mode-state
  "The machine tree's mode view: free (multi), pinned to an app (solo), or locked."
  []
  (cond
    (locked?) {:mode "locked"}
    (solo?)   {:mode "solo" :app (name (:app @state*))}
    :else     {:mode "multi"}))


(defn current-apps-state [] (or @prev* {:mode :multi :running [] :catalog [] :current nil}))
