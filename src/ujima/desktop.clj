(ns ujima.desktop
  "Wires the desktop shell: load the baked catalog, hold the projection, subscribe to i3, serve the
   eww-facing API, and bring up eww itself. Called from ujima.core AFTER control/init! + reconcile!,
   so the shell that appears is the finished, reconciled desktop.
   cfg = {:catalog <path> :http {:host :port} :chromium <bin> :profile-dir <dir> :eww-config <dir>}."
  (:require [babashka.fs :as fs]
            [ujima.log :as log]
            [ujima.desktop.catalog   :as catalog]
            [ujima.desktop.windows   :as windows]
            [ujima.desktop.lifecycle :as lc]
            [ujima.desktop.eww       :as eww]
            [ujima.desktop.i3        :as i3]
            [ujima.desktop.http      :as http]))


(def ^:private opening-timeout-ms
  "How long an app may sit :opening before we give up on it (crash, or a class that never matches)."
  12000)


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
   up (opening-timeout-ms) — dropping the loading overlay if that empties the wait. Idle otherwise —
   a light heartbeat that touches no i3/get_tree when nothing is launching. Starts before open-shell!."
  [state* lifecycle* eww-config handle]
  (future
    (loop []
      (try
        (when (lc/awaiting? @lifecycle*)
          (sync! state* handle)
          (doseq [id (lc/expired @lifecycle* (System/currentTimeMillis) opening-timeout-ms)]
            (log/warn "app never appeared — giving up" {:app id})
            (swap! lifecycle* lc/forget id))
          (when-not (lc/awaiting? @lifecycle*) (eww/loading! eww-config false)))
        (catch Throwable e (log/error "sync failed" {:error (ex-message e)})))
      (Thread/sleep (if (lc/awaiting? @lifecycle*) 400 1000))
      (recur))))


(defn init! [cfg]
  (let [cat        (catalog/load! (:catalog cfg))
        state*     (atom (windows/init-state cat))
        lifecycle* (atom {})
        eww-config (or (:eww-config cfg) "/opt/ujima/desktop/eww")
        ctx        {:state*     state*
                    :lifecycle* lifecycle*
                    :eww-config eww-config
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
                     ;; the sync loop / a title event) — place it, mark its app :running, and once
                     ;; nothing is left :opening, drop the loading overlay to reveal it.
                     (when (and (#{:window/new :window/title} (:type ev)) (nil? before-wid) after-wid)
                       (i3/place! con after-wid)
                       (swap! lifecycle* lc/running (:app-id (windows/window @state* after-wid)))
                       (when-not (lc/awaiting? @lifecycle*) (eww/loading! eww-config false)))
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
    (sync-loop! state* lifecycle* eww-config handle)                ; gated get_tree net — before eww
    (log/info "opening shell" {:eww eww-config})
    (eww/open-shell! eww-config)))                                   ; brings up the shell; this BLOCKS
