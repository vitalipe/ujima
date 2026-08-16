(ns ujima.desktop.http
  "The shell module, mounted at the root of the loopback listener: the /ui streams
   and verbs, the app catalog and icon, and the launcher's files. The verbs mirror
   /api's for this machine only — no :scope, because /ui IS the session."
  (:require [ujima.api.routes            :as routes]
            [ujima.control.commands      :as effects]
            [ujima.desktop.app           :as app]
            [ujima.desktop.http.converge :as converge]
            [ujima.desktop.http.files    :as files]
            [ujima.desktop.http.ui       :as ui]))


;; --- the verbs -----------------------------------------------------------

(def ^:private verbs
  {"app/open"     {:doc     "Open an app by catalog id."
                   :params  [:map [:app [:string {:min 1}]]]
                   :handler (fn [{id :app}] (app/run! (keyword id)))}

   "app/close"    {:doc     "Close the focused app."
                   :handler (fn [_] (app/close-focused!))}

   "app/home"     {:doc     "Go to the home workspace."
                   :handler (fn [_] (app/go-home!))}

   "app/open-url" {:doc     "Open a URL in the Web app."
                   :params  [:map [:url [:string {:min 1}]]]
                   :handler (fn [{:keys [url]}] (app/open-url! url))}

   "app/next"     {:doc     "Focus the next open app."
                   :handler (fn [_] (app/cycle! 1))}

   "app/prev"     {:doc     "Focus the previous open app."
                   :handler (fn [_] (app/cycle! -1))}

   "keyboard/layout" {:doc     "Set the layout; only codes in available-layouts are accepted."
                      :params  [:map [:layout [:string {:min 1}]]]
                      :handler (fn [{:keys [layout]}] (effects/change-keyboard-layout! layout :session))}

   "audio/muted"  {:doc     "Mute or unmute this machine."
                   :params  [:map [:value :boolean]]
                   :handler (fn [{:keys [value]}] (effects/change-setting! [:audio :muted] value :session))}

   "volume/move"  {:doc     "Record a slider position; coalesced, the last value wins."
                   :params  [:map [:value [:or :int :double]]]
                   :handler (fn [{:keys [value]}] (ui/volume-moved! value))}})


;; --- what this module serves ---------------------------------------------

(defn endpoints [{:keys [static-root]}]
  {:errors {:app/unknown-app 404
            :app/bad-url     400}

   :routes
   (merge
     (routes/commands {:base "ui" :commands verbs})

     {"GET  /ui/state"                 (fn [req] (converge/stream-ui   req))
      "GET  /ui/apps"                  (fn [req] (converge/stream-apps req))
      "GET  /ui/keyboard/layout/next"  (fn [_] {:status 200 :body (ui/keyboard-next)})

      "GET  /app/catalog"  (fn [_] {:status 200 :body {:apps (app/catalog-listing)}})
      "GET  /app/icon/*"   (fn [{[id] :path-params}] (files/icon-file id))

      "GET  /launcher/**"  (fn [{[tail] :path-params}] (files/static-file static-root "launcher" tail))
      "GET  /icons/**"     (fn [{[tail] :path-params}] (files/static-file static-root "icons" tail))
      "GET  /wall.png"     (fn [_] (files/wall static-root "wall.png"))
      "GET  /wall.svg"     (fn [_] (files/wall static-root "wall.svg"))})})
