(ns ujima.desktop
  "Wires the desktop shell: load the baked catalog, hold the projection, subscribe to i3 (the
   single writer), serve the eww-facing API, and bring up eww itself. Called from ujima.core AFTER
   control/init! + reconcile!, so the shell that appears is the finished, reconciled desktop.
   cfg = {:catalog <path> :http {:host :port} :chromium <bin> :profile-dir <dir> :eww-config <dir>}."
  (:require [babashka.fs :as fs]
            [lib.shell   :as shell]
            [ujima.log :as log]
            [ujima.desktop.catalog :as catalog]
            [ujima.desktop.windows :as windows]
            [ujima.desktop.i3      :as i3]
            [ujima.desktop.http    :as http]))


(defn- open-eww!
  "Bring up the eww surfaces (auto-starts the daemon)."
  [cfg]
  (let [dir (or (:eww-config cfg) "/opt/ujima/desktop/eww")]
    (log/info "opening shell" {:eww dir})
    (shell/sh! :eww :--config dir "open-many" "topbar" "launcher" "dock")))


(defn init! [cfg]
  (let [cat    (catalog/load! (:catalog cfg))
        state* (atom (windows/init-state cat))
        ctx    {:state*     state*
                :subs*      (atom #{})
                :catalog    cat
                :launch-ctx {:chromium (:chromium cfg) :profile-dir (:profile-dir cfg)}}
        ;; single writer: only this handler mutates the projection; commands flow back as events
        handle (fn [ev]
                 ;; eww died if the launcher's window closes — it's the one eww window we track and
                 ;; never close ourselves, so its close == eww's process is gone. Exit so the wrapper's
                 ;; `i3-msg exit` tears the session down and systemd cold-rebuilds it (Model 1).
                 (when (and (= :window/close (:type ev))
                            (windows/launcher-con? @state* (:con-id ev)))
                   (log/error "launcher window gone (eww crashed) — exiting for a clean session rebuild")
                   (System/exit 1))
                 (let [before (windows/current @state*)]
                   (swap! state* windows/apply-event ev)
                   (when (= :window/new (:type ev))
                     (when-let [wid (windows/window-for-con @state* (:con-id ev))]
                       (i3/place! (:con-id ev) wid)))
                   ;; closing the focused window: i3 won't leave the now-empty workspace, so switch
                   ;; it to the new current (the launcher) ourselves.
                   (let [after (windows/current @state*)]
                     (when (and (= :window/close (:type ev)) (not= before after) (string? after))
                       (i3/command! "workspace" after)))
                   (http/broadcast! ctx)))]
    (fs/create-dirs (:profile-dir cfg))   ; chromium falls back to a shared default if its per-app
                                          ; --user-data-dir parent is missing — so apps must merge
    (log/info "desktop init" {:apps (count (catalog/apps cat))})
    (i3/subscribe! handle)                ; live window events (background thread)
    (http/start! ctx (:http cfg))         ; eww-facing API (up before eww connects)
    (open-eww! cfg)                       ; the agent brings the shell up itself
    (i3/seed! handle)))                   ; replay the launcher we just opened (subscribe may race it)
