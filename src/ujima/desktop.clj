(ns ujima.desktop
  "Wires the desktop shell: load the baked catalog, hold the projection, subscribe to i3, serve the
   eww-facing API, and bring up eww itself. Called from ujima.core AFTER control/init! + reconcile!,
   so the shell that appears is the finished, reconciled desktop.
   cfg = {:catalog <path> :http {:host :port} :chromium <bin> :profile-dir <dir> :eww-config <dir>}."
  (:require [babashka.fs :as fs]
            [lib.shell   :as shell]
            [ujima.log :as log]
            [ujima.desktop.catalog :as catalog]
            [ujima.desktop.windows :as windows]
            [ujima.desktop.i3      :as i3]
            [ujima.desktop.http    :as http]))


(defn- open-eww!
  "Bring up the eww surfaces (auto-starts the daemon). Blocks: the call holds the foreground eww
   daemon for the session's life, so it must be the LAST thing init! does."
  [cfg]
  (let [dir (or (:eww-config cfg) "/opt/ujima/desktop/eww")]
    (log/info "opening shell" {:eww dir})
    (shell/sh! :eww :--config dir "open-many" "topbar" "launcher" "dock")))


(defn- reconcile-loop!
  "The projection's safety net: every couple seconds, replay any untracked, catalog-matching i3
   window as a :window/new through `handle`. Catches windows the live event stream can't: LibreOffice
   sets its WM_CLASS *after* mapping and i3 emits no event for that, and a cold-boot subscribe can
   miss the launcher's window::new. Idempotent (adopt is a no-op for a tracked con) and lock-free,
   like the live stream. Started before the blocking open-eww!."
  [state* handle]
  (future
    (loop []
      (try
        (doseq [w (i3/tree-windows (i3/get-tree!))]
          (let [ev (i3/normalize {:change "new" :container w})]
            (when (and ev (:class ev)
                       (nil? (windows/window-for-con @state* (:con-id ev)))
                       (windows/app-for-class @state* (:class ev)))
              (handle ev))))
        (catch Throwable e (log/error "reconcile failed" {:error (ex-message e)})))
      (Thread/sleep 2000)
      (recur))))


(defn init! [cfg]
  (let [cat    (catalog/load! (:catalog cfg))
        state* (atom (windows/init-state cat))
        ctx    {:state*     state*
                :subs*      (atom #{})
                :catalog    cat
                :launch-ctx {:chromium (:chromium cfg) :profile-dir (:profile-dir cfg)}}
        ;; the projection's only mutators are the live i3 stream and the reconcile loop; both feed
        ;; events here. swap! is atomic and adopt is idempotent, so they're safe lock-free (a lock
        ;; here deadlocks: holding it across place!'s i3 commands stops the stream draining i3).
        handle (fn [ev]
                 ;; eww died if the launcher's window closes — it's the one eww window we track and
                 ;; never close ourselves, so its close == eww's process is gone. Exit so the wrapper's
                 ;; `i3-msg exit` tears the session down and systemd cold-rebuilds it (Model 1).
                 (when (and (= :window/close (:type ev))
                            (windows/launcher-con? @state* (:con-id ev)))
                   (log/error "launcher window gone (eww crashed) — exiting for a clean session rebuild")
                   (System/exit 1))
                 (let [con        (:con-id ev)
                       before-cur (windows/current @state*)
                       before-wid (windows/window-for-con @state* con)]
                   (swap! state* windows/apply-event ev)
                   (let [after-wid (windows/window-for-con @state* con)]
                     ;; a con that just became tracked (a new window, or a late WM_CLASS picked up by
                     ;; the reconcile loop / a title event) — move it to its workspace + focus it.
                     (when (and (#{:window/new :window/title} (:type ev)) (nil? before-wid) after-wid)
                       (i3/place! con after-wid))
                     (when (and (= :window/new (:type ev)) (:class ev) (nil? after-wid))
                       (log/info "unmanaged window" {:class (:class ev) :title (:title ev)})))
                   ;; closing the focused window: i3 won't leave the now-empty workspace, so switch
                   ;; it to the new current (the launcher) ourselves.
                   (let [after-cur (windows/current @state*)]
                     (when (and (= :window/close (:type ev)) (not= before-cur after-cur) (string? after-cur))
                       (i3/command! "workspace" after-cur)))
                   (http/broadcast! ctx)))]
    (fs/create-dirs (:profile-dir cfg))   ; chromium falls back to a shared default if its per-app
                                          ; --user-data-dir parent is missing — so apps must merge
    (log/info "desktop init" {:apps (count (catalog/apps cat))})
    (i3/subscribe! handle)                ; live window events (background thread)
    (http/start! ctx (:http cfg))         ; eww-facing API (up before eww connects)
    (reconcile-loop! state* handle)       ; get_tree safety net — MUST precede the blocking open-eww!
    (open-eww! cfg)))                      ; bring up the shell; this call holds the eww daemon (blocks)
