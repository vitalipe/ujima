(ns ujima.desktop.app
  "The app layer: the listener loop — observe, act, re-observe, project — and the verbs that
   feed it. The WORKSPACE is an app's identity (app :write lives on workspace \"write\", home
   is \"1\"), so windows are never matched on WM_CLASS. Each launch lands in a systemd --user
   scope: alive? and kill. i3 owns placement and focus. Verbs, i3 events and scope deaths ride
   one listener thread (i3/emit!)."
  (:require [ujima.linux.i3 :as i3]
            [ujima.desktop.app.catalog    :as catalog]
            [ujima.desktop.app.act        :as act]
            [ujima.desktop.app.projection :as proj :refer [home-ws]]))


(defonce ^:private prev*    (atom nil))   ; last snapshot, the prv of (next prv)
(defonce ^:private targets* (atom []))


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
  ;; swap-vals! so two threads can't claim the same prev; ORDER still needs the one listener
  (let [prv (first (swap-vals! prev* (constantly snapshot)))]
    (doseq [t @targets*] (t snapshot prv))))


(defn handle-event! [ev]
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
    (-> w proj/projection converge!)))


;; --- verbs: validate, then ride the pipe ---

(defn run!           [id] (i3/emit! {:type :app/run    :app (catalog/resolve! id)}))
(defn switch-to!     [id] (i3/emit! {:type :app/switch :app (catalog/resolve! id)}))
(defn close-focused! []   (i3/emit! {:type :app/close}))
(defn go-home!       []   (i3/emit! {:type :app/home}))
(defn cycle!         [step] (i3/emit! {:type :app/cycle :step step}))

(defn open-url! [url]
  (when-not (re-matches #"https?://\S+" (str url))
    (throw (ex-info "not an http url" {:error :app/bad-url :url (str url)})))
  (i3/emit! {:type :app/open-url :app (catalog/resolve! browser-app) :url url}))

(defn current-apps-state [] (or @prev* {:running [] :catalog [] :current nil}))
