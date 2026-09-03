(ns ujima.desktop.http
  "The desktop module — mounted at ujima-desktop on the loopback listener, one base
   per concern: pushed projections, verbs, the widget-shaped reads and gestures that
   are not domain state, the catalog, and files. The verbs mirror /api's for this
   machine only — no :scope, because this tier IS the session."
  (:require [ujima.api.routes            :as routes]
            [ujima.control.commands      :as effects]
            [ujima.desktop.app           :as app]
            [ujima.desktop.http.converge :as converge]
            [ujima.desktop.http.files    :as files]
            [ujima.desktop.http.ui       :as ui]))


;; --- the verbs -----------------------------------------------------------

(def ^:private verbs
  ;; no hold verbs here: the hold is the circle's, taken and handed back over the signed tier.
  ;; A machine that could release itself is not held, and the token stick's escape needs no
  ;; route — it calls app/release! in-process.
  {"app/open"     {:doc     "Open an app by catalog id."
                   :params  [:map [:app [:string {:min 1}]]]
                   :handler (fn [{:keys [app]}] (app/run! (keyword app)))}

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

   ;; a named verb, not settings/**: the generic write stays on the signed tier
   "audio/muted"  {:doc     "Mute or unmute this machine."
                   :params  [:map [:value :boolean]]
                   :handler (fn [{:keys [value]}] (effects/change-setting! [:audio :muted] value :session))}})


;; --- what this module serves ---------------------------------------------

(defn endpoints [{:keys [static-root]}]
  ;; every error a verb here can raise — an unnamed one is a 500
  {:errors {:app/unknown-app         404
            :app/bad-url             400
            :keyboard/unknown-layout 409}

   :routes
   (merge
     (routes/commands {:base "commands" :commands verbs})

     ;; the authoritative projections, pushed
     {"GET  /stream/state"  (fn [req] (converge/stream-ui   req))
      "GET  /stream/apps"   (fn [req] (converge/stream-apps req))
      "GET  /stream/places" (fn [req] (converge/stream-places req))

      ;; interaction, not state: a coalesced gesture and a read the stream doesn't carry
      "POST /ui/volume/move"           (fn [{body :body}] (ui/volume-moved! (:value body))
                                                          {:status 202 :body {}})
      "GET  /ui/keyboard/layout/next"  (fn [_] {:status 200 :body (ui/keyboard-next)})

      "GET  /app/catalog"  (fn [_] {:status 200 :body {:apps (app/catalog-listing)}})

      "GET  /assets/launcher/**"  (fn [{[tail] :path-params}] (files/static-file static-root "launcher" tail))
      "GET  /assets/icons/**"     (fn [{[tail] :path-params}] (files/static-file static-root "icons" tail))
      "GET  /assets/app-icon/*"   (fn [{[id]   :path-params}] (files/icon-file id))
      "GET  /assets/wall.png"     (fn [_] (files/wall static-root "wall.png"))
      "GET  /assets/wall.svg"     (fn [_] (files/wall static-root "wall.svg"))})})
