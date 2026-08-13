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
   routes ARE the whitelist; \"../\" is refused inside the tail."
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

(defn- static-file
  "TAIL under `static-root`, joined back onto the route's own prefix. The
   router hands the tail through verbatim, so \"..\" is ours to refuse; an
   empty tail is the directory's index.html."
  [prefix tail]
  (let [tail (if (str/blank? tail) "index.html" tail)]
    (when-not (some #{".."} (str/split tail #"/"))
      (let [f   (io/file static-root (str prefix "/" tail))
            ext (some-> (re-find #"\.([^.]+)$" (.getName f)) second str/lower-case)]
        (when (.isFile f)
          {:status 200
           :headers {"content-type" (get content-types ext "application/octet-stream")}
           :body f})))))

(defn- static-root-file [name]
  (let [f (io/file static-root name)]
    (when (.isFile f)
      {:status 200
       :headers {"content-type" (get content-types (subs name (inc (str/index-of name "."))))}
       :body f})))

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
    "GET  /wall.png"     (fn [_] (static-root-file "wall.png"))
    "GET  /wall.svg"     (fn [_] (static-root-file "wall.svg"))}})
