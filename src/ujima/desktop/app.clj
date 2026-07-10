(ns ujima.desktop.app
  "The app layer: a small write side (run / switch / close / home) and a projection.
   The WORKSPACE is an app's identity — app :write lives on workspace \"write\", home is
   \"1\" — so we never match on WM_CLASS and never chase a window. Verbs ride the same
   listener thread as i3 events (i3/emit!); each event re-reads the tree and converges a
   snapshot to the UI targets. i3 owns placement and focus; we only switch and launch."
  (:require [babashka.fs      :as fs]
            [babashka.process :as p]
            [lib.io    :as io]
            [lib.shell :as shell]
            [ujima.log :as log]
            [ujima.linux.i3            :as i3]
            [ujima.desktop.app.catalog :as catalog]))


(defonce ^:private catalog* (atom nil))
(defonce ^:private prev*    (atom nil))   ; last snapshot, the prv of (next prv)
(defonce ^:private targets* (atom []))
(defonce ^:private ran*     (atom {}))    ; app-id -> launched-at, debounces re-launch


(def ^:private home-ws      "1")
(def ^:private browser-app  :web)
(def ^:private run-guard-ms 6000)         ; debounces re-launch while an app is still coming up


(defn load-catalog [path]
  (when-not (and path (fs/exists? (str path)))
    (throw (ex-info "app catalog not found" {:path (str path)})))
  (let [raw (io/slurp-edn path)]
    (when-not (map? raw)
      (throw (ex-info "app catalog unreadable" {:path (str path)})))
    (catalog/->catalog raw)))


(defn init! [{:keys [catalog converge-targets]}]
  (reset! catalog* catalog)
  (reset! prev*    nil)
  (reset! ran*     {})
  (reset! targets* (vec converge-targets))
  catalog)


(defn catalog-listing [] (catalog/listing @catalog*))


;; --- observe + projection (the read side) ---

(defn- observe! []
  {:focused-ws (i3/focused-workspace)
   :ws->wins   (group-by :workspace (i3/window-facts (i3/get-tree!)))})


(defn- app-of-ws [ws]
  (when (and ws (not= ws home-ws))
    (first (filter #(= (name %) ws) (:order @catalog*)))))


(defn- entry [id ws->wins]
  (let [a    (get-in @catalog* [:by-id id])
        wins (get ws->wins (name id))]
    {:id id :label (:label a) :icon (:icon a) :category (:category a)
     :title (:title (or (first (filter :focused? wins)) (first wins)))
     :fullscreen (= :fullscreen (:mode a))}))


(defn- projection [{:keys [focused-ws ws->wins]}]
  (let [open (->> (:order @catalog*)
                  (filter #(seq (get ws->wins (name %))))
                  (mapv #(entry % ws->wins)))]
    {:apps    open
     :current (when-let [id (app-of-ws focused-ws)] (entry id ws->wins))}))


(defn- converge! [snapshot]
  (let [prv @prev*]
    (reset! prev* snapshot)
    (doseq [t @targets*] (t snapshot prv))))


;; --- act (the write side; listener thread only) ---

(defn- recently-ran? [id]
  (< (- (System/currentTimeMillis) (get @ran* id 0)) run-guard-ms))


(defn- spawn! [exec]
  (apply shell/sh {:out :inherit :err :inherit :shutdown p/destroy-tree} exec))


(defn- do-run!
  "Switch to the app's workspace, then launch it only if that workspace is empty and we
   didn't just launch it. The window maps onto the focused workspace — it comes to us."
  [{:keys [ws->wins]} {:keys [id exec]} extra]
  (let [ws (name id)]
    (i3/switch-workspace! ws)
    (when (and (empty? (get ws->wins ws)) (not (recently-ran? id)))
      (try
        (spawn! (into (vec exec) extra))
        (swap! ran* assoc id (System/currentTimeMillis))
        (log/info "app launched" {:app id})
        (catch Throwable e
          (log/error "app launch failed" {:app id :error (ex-message e)})
          (i3/switch-workspace! home-ws))))))


(defn- do-close! [{:keys [focused-ws]}]
  (when (app-of-ws focused-ws)                 ; never the launcher
    (i3/kill-focused!)))


(defn- maybe-go-home!
  "A window closed and left the visible workspace empty -> home. Guarded by recently-ran? so a
   just-launched app that hasn't mapped its window yet isn't fled."
  [{:keys [focused-ws ws->wins]}]
  (when (and focused-ws (not= focused-ws home-ws)
             (empty? (get ws->wins focused-ws))
             (not (recently-ran? (keyword focused-ws))))
    (log/info "workspace emptied — going home" {:ws focused-ws})
    (i3/switch-workspace! home-ws)))


(defn- settle-floaters!
  "chromium --app windows auto-float (fixed size hints) and set class/role only AFTER mapping,
   so i3's for_window can't catch them. Un-float any floating non-dialog window on an app
   workspace so it fills the workspace under the bars. Idempotent (acts only on floaters)."
  [{:keys [ws->wins]}]
  (doseq [[ws wins] ws->wins
          {:keys [con-id floating? wtype]} wins
          :when (and (app-of-ws ws) floating?
                     (not (#{"dialog" "utility" "splash"} wtype)))]
    (i3/command? (format "[con_id=%d]" con-id) "floating" "disable")))


(defn handle-event! [ev]
  (case (:type ev)
    :app/run      (do-run! (observe!) (:app ev) (:extra ev []))
    :app/switch   (i3/switch-workspace! (name (:id (:app ev))))
    :app/close    (do-close! (observe!))
    :app/home     (i3/switch-workspace! home-ws)
    :window/close (maybe-go-home! (observe!))
    nil)

  (let [w (observe!)]
    (settle-floaters! w)
    (-> w projection converge!)))


;; --- verbs: validate, then ride the pipe ---

(defn- resolve! [id]
  (or (get-in @catalog* [:by-id id])
      (throw (ex-info "unknown app" {:error :app/unknown-app :id id}))))

(defn run!           [id] (i3/emit! {:type :app/run    :app (resolve! id)}))
(defn switch-to!     [id] (i3/emit! {:type :app/switch :app (resolve! id)}))
(defn close-focused! []   (i3/emit! {:type :app/close}))
(defn go-home!       []   (i3/emit! {:type :app/home}))

(defn open-url! [url]
  (when-not (re-matches #"https?://\S+" (str url))
    (throw (ex-info "not an http url" {:error :app/bad-url :url (str url)})))
  (i3/emit! {:type :app/run :app (resolve! browser-app) :extra [url]}))

(defn current-apps-state [] (or @prev* {:apps [] :current nil}))
