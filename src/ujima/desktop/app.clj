(ns ujima.desktop.app
  "The app layer: a small write side (run / switch / close / home) and a projection.
   The WORKSPACE is an app's identity — app :write lives on workspace \"write\", home is \"1\" —
   so we never match on WM_CLASS. Each launch lands in a systemd --user scope
   (ujima.linux.systemd), which answers 'is this app alive?' (the launch gate + the go-home
   backstop) and 'kill it' (force-close). i3 owns placement/focus; the tree owns display.
   Verbs + i3 events + scope-death events all ride one listener thread (i3/emit!)."
  (:require [babashka.fs :as fs]
            [lib.io    :as io]
            [lib.shell :as shell]
            [ujima.log :as log]
            [ujima.linux.i3      :as i3]
            [ujima.linux.systemd :as systemd]
            [ujima.desktop.app.catalog :as catalog]))


(defonce ^:private catalog* (atom nil))
(defonce ^:private prev*    (atom nil))   ; last snapshot, the prv of (next prv)
(defonce ^:private targets* (atom []))
(defonce ^:private close*   (atom nil))   ; last ✕: {:con :app :at} — drives con-id go-home + ✕✕


(def ^:private home-ws     "1")
(def ^:private browser-app :web)
(def force-lo-ms 1000)                     ; a 2nd ✕ sooner = accidental double-click (ignore)
(def force-hi-ms 3000)                     ; a 2nd ✕ later = a fresh close, not an escalation


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
  (reset! close*   nil)
  (reset! targets* (vec converge-targets))
  catalog)


(defn catalog-listing [] (catalog/listing @catalog*))


;; --- observe + projection (read side; the tree = display) ---

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
     ;; detected (the window is really fullscreen) OR declared (:mode) — declared is the escape
     ;; hatch for an app that draws full-screen without setting the fullscreen state
     :fullscreen (boolean (or (some :fullscreen? wins) (= :fullscreen (:mode a))))}))


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


;; --- act (write side; listener thread only). scope = lifecycle ---

(defn- do-run!
  "Switch to the app's workspace, then launch into a scope only if it isn't already up. The
   scope is the gate: an app still cold-starting counts as up, so a re-tap never double-spawns."
  [{:keys [id exec]} extra]
  (i3/switch-workspace! (name id))
  (when-not (systemd/active? id)
    (try
      (systemd/spawn-scoped! id (into (vec exec) extra))
      (log/info "app launched" {:app id})
      (catch Throwable e
        (log/error "app launch failed" {:app id :error (ex-message e)})
        (i3/switch-workspace! home-ws)))))


(defn- do-open-url!
  "A link -> the Web app. Warm: a plain messenger joins the running instance (its window lands
   in the existing scope). Cold: a normal scoped launch with the url. Either way, switch there."
  [{:keys [id exec] :as app} url]
  (if (systemd/active? id)
    (do (apply shell/sh {:out :inherit :err :inherit} (conj (vec exec) url))
        (i3/switch-workspace! (name id)))
    (do-run! app [url])))


(defn- do-close!
  "First ✕ = polite WM_DELETE (the app owns save-prompts etc.), arming a record. A deliberate 2nd
   ✕ on the SAME still-focused window in the 1-3s window = force-kill the scope. No focused window
   on an app workspace (zombie / launch to abort) = force-kill too."
  [{:keys [focused-ws ws->wins]}]
  (when-let [app (app-of-ws focused-ws)]
    (let [con (:con-id (first (filter :focused? (get ws->wins focused-ws))))
          now (System/currentTimeMillis)
          rec @close*]
      (cond
        (nil? con)
        (do (systemd/stop! app) (reset! close* nil))

        (and rec (= app (:app rec)) (= con (:con rec))
             (<= force-lo-ms (- now (:at rec)) force-hi-ms))
        (do (log/warn "force-close" {:app app})
            (systemd/stop! app) (reset! close* nil))

        :else
        (do (i3/kill-focused!)
            (reset! close* {:con con :app app :at now}))))))


(defn- go-home-if-empty!
  "Go home only if we're looking at APP-ID's now-empty workspace — idempotent across the con-id
   and scope-death triggers, and never fires for a background app dying elsewhere."
  [app-id {:keys [focused-ws ws->wins]}]
  (when (and (= focused-ws (name app-id)) (empty? (get ws->wins focused-ws)))
    (log/info "empty app workspace — going home" {:ws focused-ws})
    (i3/switch-workspace! home-ws)))


(defn- settle-floaters!
  "chromium --app auto-floats (fixed size hints) and sets class/role only AFTER mapping, so i3's
   for_window can't catch it. Un-float floating non-dialog windows on app workspaces. Idempotent."
  [{:keys [ws->wins]}]
  (doseq [[ws wins] ws->wins
          {:keys [con-id floating? wtype]} wins
          :when (and (app-of-ws ws) floating?
                     (not (#{"dialog" "utility" "splash"} wtype)))]
    (i3/command? (format "[con_id=%d]" con-id) "floating" "disable")))


(defn- route-windows!
  "The orphan backstop: a window that mapped on the wrong workspace (focus moved away during the
   launch) is moved to its app's workspace, matched by WM_CLASS. No focus change, so it goes to its
   place while the kid stays put; dialogs stay with their parent; idempotent (already-home = no-op)."
  [{:keys [ws->wins]}]
  (let [by-class (:by-class @catalog*)]
    (doseq [[ws wins] ws->wins
            {:keys [con-id class wtype]} wins
            :let  [target (get by-class class)]
            :when (and target (not= ws (name target))
                       (not (#{"dialog" "utility" "splash"} wtype)))]
      (i3/command? (format "[con_id=%d]" con-id) "move" "container" "to" "workspace" (name target)))))


(defn handle-event! [ev]
  (case (:type ev)
    :app/run      (do-run! (:app ev) (:extra ev []))
    :app/switch   (i3/switch-workspace! (name (:id (:app ev))))
    :app/open-url (do-open-url! (:app ev) (:url ev))
    :app/close    (do-close! (observe!))
    :app/home     (i3/switch-workspace! home-ws)
    :window/close (when-let [rec @close*]                       ; the window the user ✕'d closed
                    (when (= (:con-id ev) (:con rec))
                      (reset! close* nil)
                      (let [app (:app rec) w (observe!)]
                        ;; its workspace is now empty -> the user's close took: make sure the app is
                        ;; really gone (a still-launching process would else re-map a stray window)
                        (when (empty? (get (:ws->wins w) (name app)))
                          (systemd/stop! app)
                          (go-home-if-empty! app w)))))
    :scope/died   (go-home-if-empty! (:app-id ev) (observe!))   ; crash / self-quit backstop
    nil)

  (let [w (observe!)]
    (settle-floaters! w)
    (route-windows! w)
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
  (i3/emit! {:type :app/open-url :app (resolve! browser-app) :url url}))

(defn current-apps-state [] (or @prev* {:apps [] :current nil}))
