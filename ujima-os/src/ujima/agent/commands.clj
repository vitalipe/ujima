(ns ujima.agent.commands
  (:require [ujima.runtime.protocols :as runtime]))


(defn hostname+settings! [runtime* hostname]
  (let [settings     (runtime/settings runtime*)
        new-settings (assoc-in settings [:system :hostname] hostname)]

    (runtime/settings! runtime* new-settings)
    (runtime/hostname! runtime* hostname)
    (runtime/settings runtime*)))


(defn timezone+settings! [runtime* timezone]
  (let [settings     (runtime/settings runtime*)
        new-settings (assoc-in settings [:system :timezone] timezone)]

    (runtime/settings! runtime* new-settings)
    (runtime/timezone! runtime* timezone)
    (runtime/settings runtime*)))


(defn keyboard-layouts+settings! [runtime* layouts]
  (let [layouts       (vec layouts)
        settings      (runtime/settings runtime*)
        new-settings  (assoc-in settings [:system :keyboard-layouts] layouts)]

    (runtime/settings! runtime* new-settings)
    (runtime/keyboard-layouts! runtime* layouts)
    (runtime/settings runtime*)))


(defn wallpaper+settings! [runtime* path]
  (let [settings     (runtime/settings runtime*)
        new-settings (assoc-in settings [:desktop :wallpaper] path)]

    (runtime/settings! runtime* new-settings)
    (runtime/wallpaper! runtime* path)
    (runtime/settings runtime*)))