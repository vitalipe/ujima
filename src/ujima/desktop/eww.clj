(ns ujima.desktop.eww
  "Thin wrappers over the eww client for the surfaces the agent drives. Separate from ujima.desktop
   so both the agent and the http layer can toggle eww (the loading overlay) without a require cycle."
  (:require [lib.shell :as shell]))


(defn open-shell!
  "Open the persistent surfaces (topbar/launcher/dock). Blocks: this first eww call holds the
   foreground daemon for the session, so it must be the LAST thing init! does."
  [dir]
  (shell/sh! :eww :--config dir "open-many" "topbar" "launcher" "dock"))


(defn loading!
  "Show/hide the full-screen loading overlay. Non-throwing — toggling an already-open/closed window
   is a harmless no-op, and a flaky eww call must never crash the i3 event handler."
  [dir show?]
  (shell/sh? :eww :--config dir (if show? "open" "close") "loading"))
