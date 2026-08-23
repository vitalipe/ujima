(ns ujima.desktop.app.act
  "The effects on i3 and the scopes; listener thread only. Each takes the observed world."
  (:require [babashka.fs :as fs]
            [lib.shell :as shell]
            [ujima.log :as log]
            [ujima.linux.i3      :as i3]
            [ujima.linux.systemd :as systemd]
            [ujima.desktop.app.projection :as proj :refer [home-ws]]))


(defonce ^:private bins*  (atom {}))    ; :open-web-app-bin :serve-web-app-bin
(defonce ^:private close* (atom nil))   ; last close: {:con :app :at}


(def ^:private launcher-class "ujima-launcher")   ; not an app, lives home
(def force-lo-ms 1000)                     ; a 2nd close sooner is a double-click
(def force-hi-ms 3000)                     ; a 2nd close later is a fresh close


(defn init! [bins]
  (reset! bins*  bins)
  (reset! close* nil))


(defn app->runnable
  "BINS + SPEC -> argv, at spawn time — the one seam where a kind becomes a process. :exec runs
   as-authored (cwd = the app dir); :web-app serves <dir>/app on :port; :link opens :url.
   Trusts the scan's validation."
  [{:keys [open-web-app-bin serve-web-app-bin]} {:keys [kind exec dir entry port url class]}]
  (case kind
    :exec    (vec exec)
    :web-app [serve-web-app-bin (str (fs/path dir "app")) (str entry) (str port) class]
    :link    [open-web-app-bin (str url) class]))


(defn run!
  "Switch to the app's workspace, then launch into a scope unless one is up — a cold-starting
   app counts as up, so a re-run never double-spawns."
  [{:keys [id dir] :as app} extra]
  (i3/switch-workspace! (name id))
  (when-not (systemd/active? id)
    (try
      (systemd/spawn-scoped! id (into (app->runnable @bins* app) extra) dir
                             (when-some [env (:env app)] {:extra-env env}))
      (log/info "app launched" {:app id})
      (catch Throwable e
        (log/error "app launch failed" {:app id :error (ex-message e)})
        (i3/switch-workspace! home-ws)))))


(defn open-url!
  "A url -> the Web app: warm joins the running instance by messenger, cold is a scoped launch
   with the url. Either way, switch there."
  [{:keys [id dir] :as app} url]
  (if (systemd/active? id)
    (do (apply shell/sh {:out :inherit :err :inherit :dir dir} (conj (app->runnable @bins* app) url))
        (i3/switch-workspace! (name id)))
    (run! app [url])))


(defn close!
  "First close = polite WM_DELETE, arming a record. A 2nd on the SAME still-focused window
   1-3s later = force-kill the scope. No focused window on an app workspace (zombie / aborted
   launch) = force-kill too."
  [{:keys [focused-ws ws->wins] :as world}]
  (when-let [app (proj/app-of-ws world focused-ws)]
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


(defn go-home-if-empty!
  "Go home only when looking at APP-ID's now-empty workspace — never for an app dying elsewhere."
  [{:keys [focused-ws ws->wins]} app-id]
  (when (and (= focused-ws (name app-id)) (empty? (get ws->wins focused-ws)))
    (log/info "empty app workspace — going home" {:ws focused-ws})
    (i3/switch-workspace! home-ws)))


(defn window-closed!
  "CON-ID closed. Only the window close! asked for matters: its workspace now empty means the
   close took — stop the scope (a still-launching process would re-map a window), then home."
  [{:keys [ws->wins] :as world} con-id]
  (when-let [rec @close*]
    (when (= con-id (:con rec))
      (reset! close* nil)
      (let [app (:app rec)]
        (when (empty? (get ws->wins (name app)))
          (systemd/stop! app)
          (go-home-if-empty! world app))))))


(defn cycle!
  "Hop the ring of running apps in catalog order, STEP at a time. Home is not a stop: from
   outside the ring enter at the first app forward / the last backward. Empty ring = no-op."
  [{:keys [focused-ws] :as world} step]
  (let [ring (mapv name (proj/open-apps world))
        idx  (.indexOf ring focused-ws)]
    (when (seq ring)
      (let [target (if (neg? idx)
                     (if (pos? step) (first ring) (peek ring))
                     (nth ring (mod (+ idx step) (count ring))))]
        (when (not= target focused-ws)
          (i3/switch-workspace! target))))))


(defn fill-screen!
  "Solo: drop the bar gaps on EVERY workspace so the one app fills the screen. NOT i3
   fullscreen — a tiled dialog (the file chooser) still covers via the tabbed layout and
   stays usable. `all`, not `current`: one restore-gaps! then cleans up every workspace a
   re-solo (X->Y) touched, so a backgrounded app can't be left rendering under the bars."
  []
  (i3/try-command! "gaps" "top"    "all" "set" "0")
  (i3/try-command! "gaps" "bottom" "all" "set" "0"))


(defn stop-app!
  "Force-stop a named app's scope (unlock closes the lock app)."
  [id]
  (systemd/stop! id))

(defn restore-gaps!
  "Leaving solo: put the bar gaps back on every workspace."
  []
  (i3/try-command! "gaps" "top"    "all" "set" "48")  ; keep in sync with i3 config `gaps top`  + eww topbar height
  (i3/try-command! "gaps" "bottom" "all" "set" "68")) ; keep in sync with i3 config `gaps bottom` + eww dock height


(defn settle-floaters!
  "chromium --app floats itself after mapping (class/role arrive too late for i3's for_window).
   Un-float floating non-dialog windows on app workspaces. Idempotent."
  [{:keys [ws->wins] :as world}]
  (doseq [[ws wins] ws->wins
          {:keys [con-id floating? wtype]} wins
          :when (and (proj/app-of-ws world ws) floating?
                     (not (#{"dialog" "utility" "splash"} wtype)))]
    (i3/try-command! (format "[con_id=%d]" con-id) "floating" "disable")))


(defn route-windows!
  "A window that mapped on the wrong workspace (focus moved during the launch) goes where its
   WM_CLASS says — the app's own dialogs included, they can map before the main window. The
   launcher is not in the catalog, so it has its own rule: home. No focus change; idempotent."
  [{:keys [ws->wins catalog]}]
  (let [by-class (:by-class catalog)]
    (doseq [[ws wins] ws->wins
            {:keys [con-id class]} wins
            :let  [target (if (= launcher-class class)
                            home-ws
                            (some-> (get by-class class) name))]
            :when (and target (not= ws target))]
      (i3/try-command! (format "[con_id=%d]" con-id) "move" "container" "to" "workspace" target))))
