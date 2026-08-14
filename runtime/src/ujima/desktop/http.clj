(ns ujima.desktop.http
  "The shell module, mounted at the root: /ui/** the streams and interaction verbs, /app/**
   the catalog, icons and cycle keybinds, plus the launcher statics. The static routes are the
   whitelist and containment is checked after the path resolves."
  (:require [clojure.string  :as str]
            [clojure.java.io :as io]
            [ujima.desktop.app      :as app]
            [ujima.desktop.converge :as converge]
            [ujima.desktop.http.ui  :as ui]))


(def ^:private static-root "/ujima/desktop/shell")

(def ^:private content-types
  {"html" "text/html; charset=utf-8" "css" "text/css" "js" "text/javascript"
   "svg" "image/svg+xml" "png" "image/png" "json" "application/json"})


;; --- file serving: statics + the app icon --------------------------------

(defn- serve
  "A file from the shell tree, or nil — containment checked after resolution."
  [f]
  (let [root (str (.getCanonicalPath (io/file static-root)) "/")
        ext  (some-> (re-find #"\.([^.]+)$" (.getName f)) second str/lower-case)]
    (when (and (.isFile f) (str/starts-with? (.getCanonicalPath f) root))
      {:status 200
       :headers {"content-type" (get content-types ext "application/octet-stream")}
       :body f})))

(defn- static-file
  "TAIL under DIR; an empty tail is that directory's index.html."
  [dir tail]
  (serve (io/file static-root dir (if (str/blank? tail) "index.html" tail))))

(defn- icon-file
  "The catalog-resolved icon, so the launcher never sees the layout."
  [id]
  (let [f (some-> (app/icon-path (keyword id)) io/file)]
    (if (and f (.isFile f))
      {:status 200 :headers {"content-type" "image/svg+xml"} :body f}
      {:status 404 :body {:error "unknown app"}})))


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
    "GET  /app/icon/*"   (fn [{[id] :path-params}] (icon-file id))

    "GET  /launcher/**"  (fn [{[tail] :path-params}] (static-file "launcher" tail))
    "GET  /icons/**"     (fn [{[tail] :path-params}] (static-file "icons" tail))
    "GET  /wall.png"     (fn [_] (serve (io/file static-root "wall.png")))
    "GET  /wall.svg"     (fn [_] (serve (io/file static-root "wall.svg")))}})
