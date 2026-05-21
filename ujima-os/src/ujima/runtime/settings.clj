(ns ujima.runtime.settings
  (:require [ujima.runtime.protocol :as runtime]))

            
;; mainly for the REPL
(defn runtime-settings [runtime*]
  {:system {:hostname (runtime/hostname runtime*)
            :timezone (runtime/timezone runtime*)}

   :desktop {:wallpaper        (runtime/wallpaper runtime*)
             :keyboard-layouts (runtime/keyboard-layouts runtime*)}})


(defn reconcile-settings! [runtime* desired-settings]
  ;; sync hostname
  (when-let [hostname (get-in desired-settings [:system :hostname])]
    (when-not (= hostname (runtime/hostname runtime*))
      (runtime/hostname! runtime* hostname)))

  ;; sync timezone
  (when-let [timezone (get-in desired-settings [:system :timezone])]
    (when-not (= timezone (runtime/timezone runtime*))
      (runtime/timezone! runtime* timezone)))

  ;; sync wallpaper
  (when-let [wallpaper (get-in desired-settings [:desktop :wallpaper])]
    (when-not (= wallpaper (runtime/wallpaper runtime*))
      (runtime/wallpaper! runtime* wallpaper)))

  ;; sync keyboard layouts
  (when-let [keyboard-layouts (get-in desired-settings [:desktop :keyboard-layouts])]
    (when-not (= keyboard-layouts (runtime/keyboard-layouts runtime*))
      (runtime/keyboard-layouts! runtime* keyboard-layouts))))


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
