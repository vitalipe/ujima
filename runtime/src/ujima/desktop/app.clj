(ns ujima.desktop.app
  "The app layer. The WORKSPACE is an app's identity (app :write lives on workspace \"write\",
   home is \"1\"), so windows are never matched on WM_CLASS. A mode (Multi/Solo) is enter! /
   exit! / act! / project!; handle-event! runs them under one lock."
  (:require [ujima.linux.i3 :as i3]
            [ujima.desktop.app.catalog    :as catalog]
            [ujima.desktop.app.act        :as act]
            [ujima.desktop.app.projection :as proj :refer [home-ws]]))


(defonce ^:private prev*     (atom nil))   ; last snapshot, the prv of (next prv)
(defonce ^:private targets*  (atom []))
(defonce ^:private lock*     (Object.))    ; every pass holds this
(defonce ^:private state*    (atom nil))   ; the current Mode (a Multi or Solo record)
(defonce ^:private relaunch* (atom 0))     ; last solo relaunch, ms — the crash-loop gate


(def ^:private browser-app :web)
(def lock-app              :ujima-desktop-lock)   ; the solo target for lock/unlock
(def ^:private relaunch-ms 3000)           ; a crashing P relaunches no faster than this


(declare handle-event! ->Multi)


(defn init! [{:keys [catalog converge-targets] :as cfg}]
  (catalog/init! catalog)
  (reset! prev*     nil)
  (reset! state*    (->Multi))
  (reset! relaunch* 0)
  (reset! targets*  (vec converge-targets))
  (act/init! (select-keys cfg [:open-web-app-bin :serve-web-app-bin]))
  catalog)


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


;; --- the two modes: handler tables (absent = refused) + how each decorates the snapshot ---

(def ^:private multi-handlers
  {:app/run       (fn [ev] (act/run! (:app ev) (:extra ev [])))
   :app/switch    (fn [ev] (i3/switch-workspace! (name (:id (:app ev)))))
   :app/open-url  (fn [ev] (act/open-url! (:app ev) (:url ev)))
   :app/close     (fn [_]  (act/close! (observe!)))
   :app/home      (fn [_]  (i3/switch-workspace! home-ws))
   :app/cycle     (fn [ev] (act/cycle! (observe!) (:step ev)))
   :window/closed (fn [ev] (act/window-closed! (observe!) (:con-id ev)))
   :scope/died    (fn [ev] (act/go-home-if-empty! (observe!) (:app-id ev)))})


(defn- solo-handlers
  "Closed over P: :scope/died fires for any app's death, so relaunch only when it was P
   (a backgrounded app from an X->Y re-solo can die too)."
  [app]
  {:scope/died (fn [ev] (when (= (:app-id ev) app) (relaunch! app)))})


;; --- Mode: a state is enter! / exit! (write-side transition) + act! / project! (the pass) ---

(defprotocol Mode
  (enter!   [m]       "set up on entering this mode")
  (exit!    [m]       "tear down on leaving it")
  (act!     [m ev]    "run this mode's handler for EV, if it admits one")
  (project! [m world] "the base projection stamped with this mode's data"))


(defrecord Multi []
  Mode
  (enter!   [_]       nil)
  (exit!    [_]       nil)
  (act!     [_ ev]    (dispatch multi-handlers ev))
  (project! [_ world] (let [snap (proj/base world)]
                        (assoc snap :mode :multi
                                    :bars-hidden? (boolean (get-in snap [:current :fullscreen]))))))


(defrecord Solo [app]
  Mode
  (enter!   [_]       (act/run! (catalog/resolve! app) []) (act/fill-screen!))
  (exit!    [_]       (act/restore-gaps!)
                      (when (= lock-app app)             ; left the lock screen: clear it away
                        (act/stop-app! app)
                        (i3/switch-workspace! home-ws)))
  (act!     [_ ev]    (dispatch (solo-handlers app) ev))
  (project! [_ world] (assoc (proj/base world) :mode :solo :bars-hidden? true)))


(defn- event->mode [ev]
  (case (:type ev)
    :mode/solo  (->Solo (:id (:app ev)))
    :mode/multi (->Multi)))


(defn handle-event!
  "One pass under the lock: a :mode/* event transitions (exit! old, swap, enter! new — always
   paired); then the mode acts on EV, settle! reconciles, and the mode projects for converge."
  [ev]
  (locking lock*
    (when (= "mode" (namespace (:type ev)))
      (exit! @state*)
      (reset! state* (event->mode ev))
      (enter! @state*))
    (act! @state* ev)
    (converge! (project! @state* (settle!)))))


;; --- verbs: validate, then one pass ---

(defn run!           [id] (handle-event! {:type :app/run    :app (catalog/resolve! id)}))
(defn switch-to!     [id] (handle-event! {:type :app/switch :app (catalog/resolve! id)}))
(defn close-focused! []   (handle-event! {:type :app/close}))
(defn go-home!       []   (handle-event! {:type :app/home}))
(defn cycle!         [step] (handle-event! {:type :app/cycle :step step}))

(defn enter-solo-mode! [id] (handle-event! {:type :mode/solo :app (catalog/resolve! id)}))
(defn exit-solo-mode!  []   (handle-event! {:type :mode/multi}))
(defn solo?  [] (instance? Solo @state*))

(defn lock!   [] (enter-solo-mode! lock-app))              ; lock = solo on the lock app
(defn unlock! [] (exit-solo-mode!))                        ; Solo/exit! stops the lock app + home
(defn locked? [] (and (solo?) (= lock-app (:app @state*)))); soloed specifically on the lock app

(defn open-url! [url]
  (when-not (re-matches #"https?://\S+" (str url))
    (throw (ex-info "not an http url" {:error :app/bad-url :url (str url)})))
  (handle-event! {:type :app/open-url :app (catalog/resolve! browser-app) :url url}))

(defn current-apps-state [] (or @prev* {:mode :multi :running [] :catalog [] :current nil}))
