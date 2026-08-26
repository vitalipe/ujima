(ns ujima.linux.i3
  "i3 over the lib.shell DSL: a window/workspace event stream plus the few commands the
   app layer issues. The binary IPC is avoided so the *spawn* seam can stub i3-msg."
  (:require [cheshire.core      :as json]
            [clojure.core.async :as async]
            [clojure.java.io    :as jio]
            [babashka.process   :as p]
            [lib.shell          :as shell]
            [ujima.log          :as log]))


(defn normalize
  "A raw i3 event -> a bare tick the app layer reacts to, or nil to ignore. We carry no
   window details: every handler re-reads the tree, so the event only says 'something moved'.
   :window/closed is kept distinct because the go-home rule keys on it."
  [ev]
  (if-let [c (:container ev)]
    (case (:change ev)
      "close" {:type :window/closed :con-id (:id c)}
      ;; "floating" too: chromium --app floats itself AFTER mapping, so we must re-settle then
      ("new" "title" "focus" "fullscreen_mode" "floating") {:type :window/changed}
      nil)
    (when (and (:current ev) (= "focus" (:change ev)))
      {:type :workspace/focused})))


(defn- parse-line [line]
  (try (json/parse-string line true) (catch Exception _ nil)))


(defn get-tree! []
  (json/parse-string (shell/sh! :i3-msg :-t "get_tree") true))


(defn window-facts
  "The tree flattened to [{:con-id :workspace :focused? :floating? :wtype :fullscreen? :class
   :instance :title}]."
  [tree]
  (letfn [(walk [node ws floating?]
            (let [ws (if (= "workspace" (:type node)) (:name node) ws)]
              (concat
                (when (:window node)
                  [{:con-id (:id node) :workspace ws :focused? (boolean (:focused node))
                    :floating? floating? :wtype (:window_type node)
                    :fullscreen? (pos? (long (or (:fullscreen_mode node) 0)))
                    :class (:class (:window_properties node))
                    :instance (:instance (:window_properties node))
                    :title (:name node)}])
                (mapcat #(walk % ws floating?) (:nodes node))
                (mapcat #(walk % ws true) (:floating_nodes node)))))]
    (vec (walk tree nil false))))


(defn focused-workspace []
  (->> (json/parse-string (shell/sh! :i3-msg :-t "get_workspaces") true)
       (some #(when (:focused %) (:name %)))))


(defn command!      [& args] (apply shell/sh! :i3-msg args))
(defn try-command!  [& args] (apply shell/sh? :i3-msg args))   ; tolerant: a con can vanish mid-tick

(defn switch-workspace! [ws] (command! "workspace" ws))
(defn kill-focused!     []   (try-command! "kill"))


(defn watch-windows!
  "Stream window + workspace events on the returned channel. One initial tick forces a first
   converge; the subscription outlives the session."
  []
  (let [ch   (async/chan 64)
        proc (shell/sh {:out :stream :err :stream :shutdown p/destroy-tree}
                       :i3-msg :-t "subscribe" :-m "[\"window\",\"workspace\"]")]
    (async/thread
      (try
        (async/>!! ch {:type :tick})
        (with-open [r (jio/reader (:out proc))]
          (doseq [line (line-seq r)]
            (when-let [ev (some-> line parse-line normalize)]
              (async/>!! ch ev))))
        (log/error "i3 event stream ended")
        (catch Throwable e
          (log/error "i3 watch died" {:error (ex-message e)}))
        (finally
          (try (p/destroy-tree proc) (catch Throwable _))
          (async/close! ch))))
    ch))
