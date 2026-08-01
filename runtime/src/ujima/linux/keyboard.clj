(ns ujima.linux.keyboard
  (:require [lib.shell :refer [$!]]))


;; Session keymap via setxkbmap — applies live to the whole X session, persists nothing:
;; the control plane re-applies at boot (converge-first), which also keeps the read-only
;; image honest. The VT console keymap is a separate mechanism, out of scope here.

(defn layout []
  (second (re-find #"(?m)^layout:\s+(\S+)" ($! setxkbmap -query))))


(defn layout! [code]
  ;; -option "" so no stale XKB option (e.g. a group toggle) outlives a layout change
  ($! setxkbmap -layout [code] -option "")
  (layout))
