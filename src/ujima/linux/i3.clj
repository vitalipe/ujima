(ns ujima.linux.i3
  "i3 as a pure event source over the lib.shell DSL. watch-windows! streams normalized
   window events from `i3-msg -t subscribe -m` (line-delimited JSON — the binary IPC is
   deliberately avoided, and the DSL keeps the *spawn* remap seam so dev/test can stub
   i3-msg). Baseline-encoded like the other watchers: every window already in the tree is
   emitted as :window/new first, so a consumer folding adoptions needs no separate sync
   pass. Consumers own all policy."
  (:require [cheshire.core      :as json]
            [clojure.core.async :as async]
            [clojure.java.io    :as jio]
            [babashka.process   :as p]
            [lib.shell          :as shell]
            [ujima.log          :as log]))


(defn normalize
  "Raw i3 `window` event (parsed JSON, keyword keys) -> a proc-store event, or nil to
   ignore (the subscribe reply, fullscreen_mode/move/floating/…). `:class` rides on
   `title` too: some apps (LibreOffice) set WM_CLASS *after* mapping, so the class only
   becomes correct on a later title event. `:transient?` flags dialogs (a child window,
   not the app's primary). Pure."
  [ev]
  (let [c          (:container ev)
        wp         (:window_properties c)
        class      (:class wp)
        transient? (some? (:transient_for wp))]
    (case (:change ev)
      "new"   {:type :window/new   :con-id (:id c) :wm-window (:window c)
               :class class :transient? transient? :title (:name c)}
      "close" {:type :window/close :con-id (:id c)}
      "title" {:type :window/title :con-id (:id c)
               :class class :transient? transient? :title (:name c)}
      "focus" {:type :window/focus :con-id (:id c)}
      nil)))


(defn- parse-line [line]
  (try (json/parse-string line true) (catch Exception _ nil)))


(defn tree-windows
  "All real X-window leaf nodes under an i3 get_tree node (those carrying a :window id)."
  [node]
  (concat (when (:window node) [node])
          (mapcat tree-windows (concat (:nodes node) (:floating_nodes node)))))


(defn get-tree!
  "Parse the live i3 layout tree (i3-msg -t get_tree)."
  []
  (json/parse-string (shell/sh! :i3-msg :-t "get_tree") true))


(defn command!
  "Run an i3 command via i3-msg (returns its reply string; throws on failure)."
  [& args]
  (apply shell/sh! :i3-msg args))


(defn kill-con!
  "WM_close the container — polite: an app may answer with a quit-confirm and keep
   the window (TuxPaint does; the SM's :closing recheck handles it)."
  [con-id]
  (command! (format "[con_id=%d]" con-id) "kill"))


(defn switch-workspace! [ws]
  (command! "workspace" ws))


(defn focused-workspace
  "The visible workspace's name (i3-msg -t get_workspaces)."
  []
  (->> (json/parse-string (shell/sh! :i3-msg :-t "get_workspaces") true)
       (some #(when (:focused %) (:name %)))))


(defn place!
  "Move a container to WORKSPACE, switch to it, and explicitly focus it. Mined from
   desktop-base: floating disable fills the workspace (chromium --app trips the i3
   pop-up float rule), sticky disable keeps it off other workspaces (eww marks
   windows sticky), and the explicit focus matters — a bare workspace switch emits
   only workspace::focus, which we don't subscribe to."
  [con-id workspace]
  (let [c (format "[con_id=%d]" con-id)]
    (command! c "floating" "disable")
    (command! c "sticky" "disable")
    (command! c "move" "to" "workspace" workspace)
    (command! "workspace" workspace)
    (command! c "focus")))


(defn baseline-events
  "Every real window in an i3 TREE as a :window/new event — what a fresh watcher emits
   so consumers adopt windows that mapped before it was listening. Pure."
  [tree]
  (keep #(normalize {:change "new" :container %}) (tree-windows tree)))


(defonce ^:private out*
  ;; the active watch channel — emit-in! delivers synthetic events onto it
  (atom nil))


(defn emit!
  "Deliver EV onto the window-event stream NOW — commands ride the same pipe as
   window events, so all handling is queue-ordered on the one listener thread.
   Dropped loudly when no watch is active or the stream has closed."
  [ev]
  (let [ch @out*]
    (when-not (and ch (async/>!! ch ev))
      (log/warn "emit!: no active window watch — event dropped" ev))))


(defn emit-in!
  "emit!, delayed by MS — the \"tell me again later\" echo (:recheck/*; the event
   carries the asker's intent identity). Pure delayed delivery: all handling
   happens downstream like any window event."
  [ms ev]
  (future
    (Thread/sleep (long ms))
    (emit! ev)))


(defn watch-windows!
  "Stream the window world on the returned channel: subscribe first, then the
   baseline (a window mapping in between arrives twice — consumers must tolerate
   replays), then live normalized events — plus the synthetic events emit-in!
   echoes back. Blocking puts. The subscription is expected to outlive the session
   (`i3-msg reload`, never `restart`); if the stream ends anyway it's logged loudly
   and the channel closes."
  []
  (let [ch   (async/chan 64)
        ;; :shutdown — finally never runs when bb itself is killed (session cycle);
        ;; without it every restart would orphan an i3-msg monitor
        proc (shell/sh {:out :stream :err :stream :shutdown p/destroy-tree}
                       :i3-msg :-t "subscribe" :-m "[\"window\"]")]
    (reset! out* ch)
    (async/thread
      (try
        (doseq [ev (baseline-events (get-tree!))]
          (async/>!! ch ev))
        (with-open [r (jio/reader (:out proc))]
          (doseq [line (line-seq r)]
            (when-let [ev (some-> line parse-line normalize)]
              (async/>!! ch ev))))
        (log/error "i3 window stream ended")
        (catch Throwable e
          (log/error "i3 window watch died" {:error (ex-message e)}))
        (finally
          (try (p/destroy-tree proc) (catch Throwable _))
          (async/close! ch))))
    ch))


(defn hint-proc!
  "Echo a proc-plane recheck: did APP-ID's spawn (identified by AT = its
   :spawned-at) ever produce a window? 25s — LibreOffice needs ~20s on the Pi."
  [app-id at]
  (emit-in! 25000 {:type :recheck/proc :app-id app-id :at at}))


(defn hint-window!
  "Echo a window-plane recheck: did CON-ID (close asked at AT) actually close?
   Past 10s a quit-confirm is holding it."
  [con-id at]
  (emit-in! 10000 {:type :recheck/window :con-id con-id :at at}))


