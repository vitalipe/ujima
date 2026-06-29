(ns ujima.desktop.i3
  "i3 bridge over the lib.shell DSL. Streams window events from `i3-msg -t subscribe -m` (one
   JSON object per line) normalized into ujima.desktop.windows events, and sends i3 commands
   (focus/close a container). The binary IPC protocol is deliberately avoided — i3-msg's monitor
   mode is the line-delimited-JSON stream we want, and going through the DSL keeps the *spawn*
   remap seam (dev/test can stub i3-msg)."
  (:require [cheshire.core   :as json]
            [clojure.java.io :as jio]
            [lib.shell       :as shell]
            [ujima.log       :as log]))


(defn normalize
  "Raw i3 `window` event (parsed JSON, keyword keys) -> a ujima.desktop.windows event, or nil to
   ignore (the subscribe reply, fullscreen_mode/move/floating/…). `:class` rides on `title` too:
   some apps (LibreOffice) set WM_CLASS *after* mapping, so the class only becomes correct on a
   later title event. `:transient?` flags dialogs (a child window, not the app's primary). Pure."
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


(defn subscribe!
  "Spawn `i3-msg -t subscribe -m [\"window\"]` and feed each normalized event to `on-event`,
   reading the monitor stream on a background thread. Returns the (started) process."
  [on-event]
  (let [proc (shell/sh :i3-msg :-t "subscribe" :-m "[\"window\"]")]
    (future
      (with-open [r (jio/reader (:out proc))]
        (doseq [line (line-seq r)]
          (when-let [ev (some-> line parse-line normalize)]
            (try (on-event ev)
                 (catch Throwable e
                   (log/error "i3 event handler failed" {:type (:type ev) :error (ex-message e)}))))))
      (log/warn "i3 event stream ended"))
    proc))


(defn tree-windows
  "All real X-window leaf nodes under an i3 get_tree node (those carrying a :window id)."
  [node]
  (concat (when (:window node) [node])
          (mapcat tree-windows (concat (:nodes node) (:floating_nodes node)))))


(defn get-tree!
  "Parse the live i3 layout tree (i3-msg -t get_tree). Used by the desktop reconcile loop."
  []
  (json/parse-string (shell/sh! :i3-msg :-t "get_tree") true))


(defn command!
  "Run an i3 command via i3-msg (returns its reply string; throws on failure)."
  [& args]
  (apply shell/sh! :i3-msg args))


(defn focus-con! [con-id] (command! (format "[con_id=%d]" con-id) "focus"))
(defn close-con! [con-id] (command! (format "[con_id=%d]" con-id) "kill"))


(defn place!
  "Move a container to its Ujima window's workspace, switch to it, and explicitly focus the
   container. The explicit focus matters: a bare `workspace` switch emits only workspace::focus
   (which we don't subscribe to), so without it the projection's `current` never follows the app."
  [con-id workspace]
  (let [c (format "[con_id=%d]" con-id)]
    (command! c "floating" "disable")   ; fill the workspace (chromium --app trips a float rule)
    (command! c "sticky" "disable")      ; stay on its own workspace (eww marks windows sticky)
    (command! c "move" "to" "workspace" workspace)
    (command! "workspace" workspace)
    (command! c "focus")))
