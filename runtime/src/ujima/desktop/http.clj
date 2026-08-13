(ns ujima.desktop.http
  "The desktop's shell module of the machine edge, mounted at the root:
     /ui/**   the GUI edge (desktop.http.ui settings, desktop.http.app apps):
              the NDJSON streams and the verbs where interaction ≠ state
              (throttled volume moves)
     /app/**  the app layer (ujima.desktop.app): the catalog and the verbs,
              plus the launcher's icon fetch (ui-of-app: file serving resolved
              through the catalog, not a verb)
   and the launcher statics — served from here (not file://) so the webview is
   same-origin with the app API and its click POSTs need no CORS. The static
   routes ARE the whitelist, and the shell tree is the only thing served: a
   path is checked for containment after resolution, so \"..\" and a symlink
   leading out of it are refused alike."
  (:require [clojure.string  :as str]
            [clojure.java.io :as io]
            [ujima.desktop.app      :as app]
            [ujima.desktop.http.app :as apps]
            [ujima.desktop.http.ui  :as ui]))


(def ^:private static-root "/ujima/desktop/shell")

(def ^:private content-types
  {"html" "text/html; charset=utf-8" "css" "text/css" "js" "text/javascript"
   "svg" "image/svg+xml" "png" "image/png" "json" "application/json"})


;; --- file serving: statics + the app icon --------------------------------

(defn- serve
  "A file from the shell tree, or nil. Containment is checked on the RESOLVED
   path — this tree is the only thing we ever serve — so \"..\", a symlink out
   of it, and anything else that leaves fail the same way."
  [f]
  (let [root (str (.getCanonicalPath (io/file static-root)) "/")
        ext  (some-> (re-find #"\.([^.]+)$" (.getName f)) second str/lower-case)]
    (when (and (.isFile f) (str/starts-with? (.getCanonicalPath f) root))
      {:status 200
       :headers {"content-type" (get content-types ext "application/octet-stream")}
       :body f})))

(defn- static-file
  "TAIL under the route's own directory; an empty tail is its index.html."
  [dir tail]
  (serve (io/file static-root dir (if (str/blank? tail) "index.html" tail))))

(defn- icon-file
  "GET /app/icon/<id> -> the catalog-resolved icon, so the launcher never
   touches the filesystem layout."
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
   {"GET  /ui/state"                 (fn [req] (ui/stream req))
    "GET  /ui/apps"                  (fn [req] (apps/stream req))
    "GET  /ui/keyboard/layout/next"  (fn [_] {:status 200 :body (ui/keyboard-next)})
    "POST /ui/volume/move"           (fn [{body :body}] (ui/volume-moved! (:value body))
                                                        {:status 202 :body {}})

    "GET  /app/catalog"  (fn [_] {:status 200 :body {:apps (app/catalog-listing)}})
    "POST /app/run"      (fn [{body :body}] (app/run! (keyword (:app-id body)))       {:status 202 :body {}})
    "POST /app/switch"   (fn [{body :body}] (app/switch-to! (keyword (:app-id body))) {:status 202 :body {}})
    "POST /app/open-url" (fn [{body :body}] (app/open-url! (:url body))               {:status 202 :body {}})
    "POST /app/close"    (fn [_]            (app/close-focused!)                      {:status 202 :body {}})
    "POST /app/home"     (fn [_]            (app/go-home!)                            {:status 202 :body {}})
    "POST /app/next"     (fn [_]            (app/cycle! 1)                            {:status 202 :body {}})
    "POST /app/prev"     (fn [_]            (app/cycle! -1)                           {:status 202 :body {}})

    "GET  /app/icon/*"   (fn [{[id] :path-params}] (icon-file id))

    "GET  /launcher/**"  (fn [{[tail] :path-params}] (static-file "launcher" tail))
    "GET  /icons/**"     (fn [{[tail] :path-params}] (static-file "icons" tail))
    "GET  /wall.png"     (fn [_] (serve (io/file static-root "wall.png")))
    "GET  /wall.svg"     (fn [_] (serve (io/file static-root "wall.svg")))}})
