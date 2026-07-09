(ns ujima.desktop
  (:require [lib.shell :as shell]
            [ujima.log :as log]
            [ujima.desktop.http :as http]
            [ujima.desktop.eww  :as eww]))


(def ^:private x-tries    60)   ; x 250ms = ~15s for X to accept an authorized connection


(defn await-x!
  []
  (loop [n x-tries]
    (when-not (:ok? (shell/sh? :setxkbmap "-query"))
      (if (pos? n)
        (do (Thread/sleep 250) (recur (dec n)))
        (log/warn "X never accepted an authorized connection — proceeding" {})))))


(defn- launcher-init!
  [bin url]
  (future
    (loop []
      (let [{:keys [exit]} @(shell/with-spawn (shell/inheriting shell/*spawn*)
                              (shell/sh {:extra-env {"UJIMA_SHELL_URL" url}} bin))]
        (log/warn "webview launcher exited — respawning" {:exit exit})
        (Thread/sleep 2000)
        (recur)))))


(defn init!! [cfg]
  
  (log/info "opening ujima shell" cfg)
    
  (http/init! (:http cfg))
  (launcher-init!  "/opt/ujima/desktop/bin/ujima-launcher" 
                    "http://127.0.0.1:1337/launcher/")


  (eww/init!! (:eww cfg))) 

