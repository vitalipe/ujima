(ns ujima.desktop
  "Brings up the static eww shell and holds it. The blocking eww call doubles as the crash
   sentinel: it returning (or throwing) means eww is gone, and the caller tears the session
   down for a cold rebuild. cfg = {:eww-config <dir>}."
  (:require [lib.shell :as shell]
            [ujima.log :as log]))


(defn init!
  "Open the eww surfaces (auto-starts the daemon). BLOCKS for the session's life."
  [cfg]
  (let [dir (or (:eww-config cfg) "/opt/ujima/desktop/eww")]
    (log/info "opening shell" {:eww dir})
    (shell/sh! :eww :--config dir "open-many" "topbar" "launcher" "dock")))
