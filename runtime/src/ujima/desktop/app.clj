(ns ujima.desktop.app
  "The app layer: one pass — observe, act, re-observe, project — under one lock, fed commands
   by the verbs and events by ujima.events. The WORKSPACE is an app's identity (app :write
   lives on workspace \"write\", home is \"1\"), so windows are never matched on WM_CLASS.
   Each launch lands in a systemd --user scope: alive? and kill. i3 owns placement and focus."
  (:require [ujima.linux.i3 :as i3]
            [ujima.desktop.app.catalog    :as catalog]
            [ujima.desktop.app.act        :as act]
            [ujima.desktop.app.projection :as proj :refer [home-ws]]))


(defonce ^:private prev*    (atom nil))   ; last snapshot, the prv of (next prv)
(defonce ^:private targets* (atom []))
(defonce ^:private lock*    (Object.))    ; every pass holds this


(def ^:private browser-app :web)


(defn init! [{:keys [catalog converge-targets] :as cfg}]
  (catalog/init! catalog)
  (reset! prev*    nil)
  (reset! targets* (vec converge-targets))
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
   :catalog    (catalog/current)})


(defn- converge! [snapshot]
  (let [prv @prev*]
    (reset! prev* snapshot)
    (doseq [t @targets*] (t snapshot prv))))


(defn handle-event!
  "One pass, whole, under the lock: the verbs' check-then-act and converge!'s read-then-reset
   of prev are only guards while a pass cannot interleave."
  [ev]
  (locking lock*
    (case (:type ev)
      :app/run       (act/run! (:app ev) (:extra ev []))
      :app/switch    (i3/switch-workspace! (name (:id (:app ev))))
      :app/open-url  (act/open-url! (:app ev) (:url ev))
      :app/close     (act/close! (observe!))
      :app/home      (i3/switch-workspace! home-ws)
      :app/cycle     (act/cycle! (observe!) (:step ev))
      :window/closed (act/window-closed! (observe!) (:con-id ev))
      :scope/died    (act/go-home-if-empty! (observe!) (:app-id ev))   ; crash / self-quit
      nil)

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

(defn open-url! [url]
  (when-not (re-matches #"https?://\S+" (str url))
    (throw (ex-info "not an http url" {:error :app/bad-url :url (str url)})))
  (handle-event! {:type :app/open-url :app (catalog/resolve! browser-app) :url url}))

(defn current-apps-state [] (or @prev* {:running [] :catalog [] :current nil}))
