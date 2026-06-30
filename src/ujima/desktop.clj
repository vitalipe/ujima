(ns ujima.desktop
  "Wires the desktop shell: load the baked catalog, hold the projection, subscribe to i3, serve the
   eww-facing API, and bring up eww itself. Called from ujima.core AFTER control/init! + reconcile!,
   so the shell that appears is the finished, reconciled desktop.
   cfg = {:catalog <path> :http {:host :port} :chromium <bin> :profile-dir <dir> :eww-config <dir>}."
  (:require [babashka.fs :as fs]
            [lib.shell   :as shell]
            [ujima.log :as log]
            [ujima.desktop.catalog   :as catalog]
            [ujima.desktop.windows   :as windows]
            [ujima.desktop.lifecycle :as lc]
            [ujima.desktop.i3        :as i3]
            [ujima.desktop.http      :as http]))


(def ^:private opening-timeout-ms
  "How long an app may sit :opening before we give up on it (crash, or a class that never matches)."
  12000)


(defn- open-eww!
  "Bring up the eww surfaces (auto-starts the daemon). Blocks: the call holds the foreground eww
   daemon for the session's life, so it must be the LAST thing init! does."
  [cfg]
  (let [dir (or (:eww-config cfg) "/opt/ujima/desktop/eww")]
    (log/info "opening shell" {:eww dir})
    (shell/sh! :eww :--config dir "open-many" "topbar" "launcher" "dock")))


(defn- sync!
  "One pass: replay any untracked, catalog-matching i3 window as a :window/new through `handle`.
   Pulls i3's *actual* tree INTO our projection (the opposite direction from settings reconcile) —
   it catches windows the live event stream can't, namely apps that set WM_CLASS *after* mapping
   (LibreOffice; i3 emits no event for that). Idempotent: handle's adopt is a no-op for a tracked con."
  [state* handle]
  (doseq [w (i3/tree-windows (i3/get-tree!))]
    (let [ev (i3/normalize {:change "new" :container w})]
      (when (and ev (:class ev)
                 (nil? (windows/window-for-con @state* (:con-id ev)))
                 (windows/app-for-class @state* (:class ev)))
        (handle ev)))))


(defn- sync-loop!
  "Run `sync!` only while an app is :opening (awaiting its window), and forget ones that never show
   up (opening-timeout-ms). Idle otherwise — a light heartbeat that touches no i3/get_tree when
   nothing is launching. Must start before the blocking open-eww!."
  [state* lifecycle* handle]
  (future
    (loop []
      (try
        (when (lc/awaiting? @lifecycle*)
          (sync! state* handle)
          (let [stale (lc/expired @lifecycle* (System/currentTimeMillis) opening-timeout-ms)]
            (doseq [id stale]
              (log/warn "app never appeared — giving up" {:app id})
              (swap! lifecycle* lc/forget id))
            ;; a staged launch never produced a window — don't strand the user on the empty staging
            ;; workspace; once nothing else is opening, return home to the launcher.
            (when (and (seq stale) (not (lc/awaiting? @lifecycle*)))
              (when-let [home (windows/window-for-app @state* :launcher)]
                (i3/command! "workspace" home)))))
        (catch Throwable e (log/error "sync failed" {:error (ex-message e)})))
      (Thread/sleep (if (lc/awaiting? @lifecycle*) 400 1000))
      (recur))))


(defn- wake-bars!
  "Each new app window resets the override-redirect bars' pointer input — they stop acting on
   clicks/hover until tapped (the double-click). After a new window is placed, tap one bar's no-op
   center to re-wake all of them, saving + restoring the pointer so the cursor doesn't jump. Runs in
   the background (xdotool warps the pointer); no-op if xdotool is absent."
  []
  (future
    (Thread/sleep 500)   ; let the new window settle — its start is what resets the bars
    (shell/sh? :bash "-c"
               "eval $(xdotool getmouselocation --shell); xdotool mousemove 640 18 click 1; xdotool mousemove $X $Y")))


(defn init! [cfg]
  (let [cat        (catalog/load! (:catalog cfg))
        state*     (atom (windows/init-state cat))
        lifecycle* (atom {})
        ctx        {:state*     state*
                    :lifecycle* lifecycle*
                    :subs*      (atom #{})
                    :catalog    cat
                    :launch-ctx {:chromium (:chromium cfg) :profile-dir (:profile-dir cfg)}}
        ;; the projection's only mutators are the live i3 stream and the sync loop; both feed events
        ;; here. swap! is atomic and adopt is idempotent, so they're safe lock-free (a lock here
        ;; deadlocks: holding it across place!'s i3 commands stops the stream draining i3).
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
                       before-wid (windows/window-for-con @state* con)
                       before-app (when before-wid (:app-id (windows/window @state* before-wid)))]
                   (swap! state* windows/apply-event ev)
                   (let [after-wid (windows/window-for-con @state* con)]
                     ;; a con that just became tracked (a new window, or a late WM_CLASS picked up by
                     ;; the sync loop / a title event) — place it + mark its app :running.
                     (when (and (#{:window/new :window/title} (:type ev)) (nil? before-wid) after-wid)
                       (i3/place! con after-wid)
                       (swap! lifecycle* lc/running (:app-id (windows/window @state* after-wid)))
                       (wake-bars!))   ; a new window resets the override-redirect bars' input — re-wake
                     (when (and (= :window/new (:type ev)) (:class ev) (nil? after-wid))
                       (log/info "unmanaged window" {:class (:class ev) :title (:title ev)})))
                   ;; the app's last window closed — drop it from the lifecycle.
                   (when (and (= :window/close (:type ev)) before-app
                              (nil? (windows/window-for-app @state* before-app)))
                     (swap! lifecycle* lc/forget before-app))
                   ;; closing the focused window: i3 won't leave the now-empty workspace, so switch
                   ;; it to the new current (the launcher) ourselves.
                   (let [after-cur (windows/current @state*)]
                     (when (and (= :window/close (:type ev)) (not= before-cur after-cur) (string? after-cur))
                       (i3/command! "workspace" after-cur)))
                   (http/broadcast! ctx)))]
    (fs/create-dirs (:profile-dir cfg))   ; chromium falls back to a shared default if its per-app
                                          ; --user-data-dir parent is missing — so apps must merge
    (log/info "desktop init" {:apps (count (catalog/apps cat))})
    (i3/subscribe! handle)                                          ; live window events (bg thread)
    (http/start! ctx (:http cfg))                                   ; eww-facing API (up before eww)
    (swap! lifecycle* lc/open :launcher (System/currentTimeMillis)) ; await the launcher we open next
    (sync-loop! state* lifecycle* handle)                           ; gated get_tree net — before open-eww!
    (open-eww! cfg)))                                                ; brings up the shell; this BLOCKS
