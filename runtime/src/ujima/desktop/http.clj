(ns ujima.desktop.http
  "The shell module, mounted at the root: the /ui streams and interaction
   verbs, the app catalog and icon, and the launcher's files."
  (:require [ujima.desktop.app           :as app]
            [ujima.desktop.http.converge :as converge]
            [ujima.desktop.http.files    :as files]
            [ujima.desktop.http.ui       :as ui]))


;; --- what this module serves ---------------------------------------------

(def endpoints
  {:errors {:app/unknown-app 404
            :app/bad-url     400}

   :routes
   {"GET  /ui/state"                 (fn [req] (converge/stream-ui   req))
    "GET  /ui/apps"                  (fn [req] (converge/stream-apps req))
    "GET  /ui/keyboard/layout/next"  (fn [_] {:status 200 :body (ui/keyboard-next)})
    "POST /ui/volume/move"           (fn [{body :body}] (ui/volume-moved! (:value body))
                                                        {:status 202 :body {}})

    "POST /ui/app/next"  (fn [_] (app/cycle! 1)  {:status 202 :body {}})
    "POST /ui/app/prev"  (fn [_] (app/cycle! -1) {:status 202 :body {}})

    "GET  /app/catalog"  (fn [_] {:status 200 :body {:apps (app/catalog-listing)}})
    "GET  /app/icon/*"   (fn [{[id] :path-params}] (files/icon-file id))

    "GET  /launcher/**"  (fn [{[tail] :path-params}] (files/static-file "launcher" tail))
    "GET  /icons/**"     (fn [{[tail] :path-params}] (files/static-file "icons" tail))
    "GET  /wall.png"     (fn [_] (files/wall "wall.png"))
    "GET  /wall.svg"     (fn [_] (files/wall "wall.svg"))}})
