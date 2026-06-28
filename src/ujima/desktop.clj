(ns ujima.desktop
  "Wires the desktop shell: load the baked catalog, hold the projection, subscribe to i3 (the
   single writer), and serve the eww-facing API. Called from ujima.core after control/init!.
   cfg = {:catalog <path> :http {:host :port} :chromium <bin> :profile-dir <dir>}."
  (:require [babashka.fs :as fs]
            [ujima.log :as log]
            [ujima.desktop.catalog :as catalog]
            [ujima.desktop.windows :as windows]
            [ujima.desktop.i3      :as i3]
            [ujima.desktop.http    :as http]))


(defn init! [cfg]
  (let [cat    (catalog/load! (:catalog cfg))
        state* (atom (windows/init-state cat))
        ctx    {:state*     state*
                :subs*      (atom #{})
                :catalog    cat
                :launch-ctx {:chromium (:chromium cfg) :profile-dir (:profile-dir cfg)}}]
    (fs/create-dirs (:profile-dir cfg))   ; chromium falls back to a shared default if its per-app
                                          ; --user-data-dir parent is missing — so apps must merge
    (log/info "desktop init" {:apps (count (catalog/apps cat))})
    ;; single writer: only this i3 event thread mutates the projection; commands flow back as events
    (i3/subscribe!
      (fn [ev]
        (swap! state* windows/apply-event ev)
        (when (= :window/new (:type ev))
          (when-let [wid (windows/window-for-con @state* (:con-id ev))]
            (i3/place! (:con-id ev) wid)))
        (http/broadcast! ctx)))
    (http/start! ctx (:http cfg))))
