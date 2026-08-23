(ns ujima.desktop.app
  "The app layer: one pass — observe, act, re-observe, project — under one lock, fed commands
   by the verbs and events by ujima.events. The WORKSPACE is an app's identity (app :write
   lives on workspace \"write\", home is \"1\"), so windows are never matched on WM_CLASS.
   Each launch lands in a systemd --user scope: alive? and kill. i3 owns placement and focus."
  (:require [ujima.linux.i3 :as i3]
            [ujima.desktop.app.catalog    :as catalog]
            [ujima.desktop.app.act        :as act]
            [ujima.desktop.app.projection :as proj :refer [home-ws]]))


(defonce ^:private prev*     (atom nil))   ; last snapshot, the prv of (next prv)
(defonce ^:private targets*  (atom []))
(defonce ^:private lock*     (Object.))    ; every pass holds this
(defonce ^:private mode*     (atom :multi)) ; :multi or [:solo <id>]
(defonce ^:private relaunch* (atom 0))     ; last solo relaunch, ms — the crash-loop gate


(def ^:private browser-app :web)
(def lock-app :ujima-desktop-lock)   ; the solo target for lock/unlock
(def ^:private relaunch-ms 3000)           ; a crashing P relaunches no faster than this


(declare handle-event!)


(defn init! [{:keys [catalog converge-targets] :as cfg}]
  (catalog/init! catalog)
  (reset! prev*     nil)
  (reset! mode*     :multi)
  (reset! relaunch* 0)
  (reset! targets*  (vec converge-targets))
  (act/init! (select-keys cfg [:open-web-app-bin :serve-web-app-bin]))
  catalog)


(defn catalog-listing [] (catalog/listing (catalog/current)))

(defn icon-path
  "The catalog-resolved icon path for ID; nil for an unknown app."
  [id]
  (get-in (catalog/current) [:by-id id :icon]))


;; --- the loop: observe, act, re-observe, project ---

(defn- observe! []
  {:focused-ws (i3/focused-workspace)
   :ws->wins   (group-by :workspace (i3/window-facts (i3/get-tree!)))
   :catalog    (catalog/current)
   :mode       @mode*})


(defn- solo-app [mode] (when (vector? mode) (second mode)))
(defn- mode-kw  [mode] (if (vector? mode) :solo :multi))


(defn- converge! [snapshot]
  (let [prv @prev*]
    (reset! prev* snapshot)
    (doseq [t @targets*] (t snapshot prv))))


(defn- relaunch-solo!
  "P (and only P) quit or crashed in solo: bring it back, but no faster than relaunch-ms —
   a P that crashes instantly reschedules instead of spinning. The reschedule re-emits
   :scope/died, so it re-dispatches on the mode current THEN (exited solo -> goes home)."
  [w app-id]
  (when (= app-id (solo-app (:mode w)))
    (let [now     (System/currentTimeMillis)
          elapsed (- now @relaunch*)]
      (if (>= elapsed relaunch-ms)
        (do (reset! relaunch* now)
            (act/run! (catalog/resolve! app-id) [])
            (act/fill-screen! (observe!)))
        (future (Thread/sleep (- relaunch-ms elapsed))
                (handle-event! {:type :scope/died :app-id app-id}))))))


;; --- the modes: each a complete type->handler table; absent = refused ---

(def ^:private multi
  {:app/run        (fn [_ ev] (act/run! (:app ev) (:extra ev [])))
   :app/switch     (fn [_ ev] (i3/switch-workspace! (name (:id (:app ev)))))
   :app/open-url   (fn [_ ev] (act/open-url! (:app ev) (:url ev)))
   :app/close      (fn [w _]  (act/close! w))
   :app/home       (fn [_ _]  (i3/switch-workspace! home-ws))
   :app/cycle      (fn [w ev] (act/cycle! w (:step ev)))
   :window/closed  (fn [w ev] (act/window-closed! w (:con-id ev)))
   :scope/died     (fn [w ev] (act/go-home-if-empty! w (:app-id ev)))})

(def ^:private solo
  {:scope/died (fn [w ev] (relaunch-solo! w (:app-id ev)))})

(def ^:private modes {:multi multi :solo solo})


(defn- transition!
  "The mode switch (both directions), run before the mode table so it works in either mode."
  [ev]
  (let [from @mode*
        to?  (= :solo (:to ev))]
    (reset! mode* (if to? [:solo (:id (:app ev))] :multi))
    (cond
      to?            (do (act/run! (:app ev) []) (act/fill-screen! (observe!)))
      (vector? from) (do (act/restore-gaps! (observe!))
                         (when (= lock-app (second from))   ; left the lock screen: clear it away
                           (act/stop-app! lock-app)
                           (i3/switch-workspace! home-ws))))))


;; --- the loop: observe, act, re-observe, project ---

(defn handle-event!
  "One pass, whole, under the lock: the verbs' check-then-act and converge!'s read-then-reset
   of prev are only guards while a pass cannot interleave. :app/mode rides ahead of the table
   (a transition must run in either mode); everything else dispatches on the current mode."
  [ev]
  (locking lock*
    (if (= :app/mode (:type ev))
      (transition! ev)
      (let [w (observe!)]
        (when-let [f (get-in modes [(mode-kw (:mode w)) (:type ev)])]
          (f w ev))))

    (let [w (observe!)]
      (act/settle-floaters! w)
      (act/route-windows! w)
      (-> w proj/projection converge!))))


;; --- verbs: validate, then one pass ---

(defn run!           [id] (handle-event! {:type :app/run    :app (catalog/resolve! id)}))
(defn switch-to!     [id] (handle-event! {:type :app/switch :app (catalog/resolve! id)}))
(defn close-focused! []   (handle-event! {:type :app/close}))
(defn go-home!       []   (handle-event! {:type :app/home}))
(defn cycle!         [step] (handle-event! {:type :app/cycle :step step}))

(defn enter-solo-mode! [id] (handle-event! {:type :app/mode :to :solo :app (catalog/resolve! id)}))
(defn exit-solo-mode!  []   (handle-event! {:type :app/mode :to :multi}))
(defn solo?  [] (vector? @mode*))

(defn lock!   [] (enter-solo-mode! lock-app))     ; lock = solo on the lock app
(defn unlock! [] (exit-solo-mode!))               ; transition! stops the lock app + goes home
(defn locked? [] (= lock-app (solo-app @mode*)))  ; soloed specifically on the lock app

(defn open-url! [url]
  (when-not (re-matches #"https?://\S+" (str url))
    (throw (ex-info "not an http url" {:error :app/bad-url :url (str url)})))
  (handle-event! {:type :app/open-url :app (catalog/resolve! browser-app) :url url}))

(defn current-apps-state [] (or @prev* {:mode :multi :running [] :catalog [] :current nil}))
