(ns ujima.agent.reconcile
  (:require [ujima.runtime.protocols :as runtime]))

            
;; mainly for the REPL
(defn runtime-settings [runtime*]
  {:system {:hostname (runtime/hostname runtime*)
            :timezone (runtime/timezone runtime*)}}

  :desktop {:wallpaper        (runtime/wallpaper runtime*)
            :keyboard-layouts (runtime/keyboard-layouts runtime*)})


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