(ns ujima.desktop.http
  "The desktop's shell tier of the machine edge (lib.http tries it):
     /ui/**   the GUI edge (desktop.http.ui settings, desktop.http.app apps):
              the NDJSON streams and the verbs where interaction ≠ state
              (throttled volume moves)
     /app/**  the app layer (ujima.desktop.app): the catalog and the verbs,
              plus the launcher's icon fetch (ui-of-app: file serving resolved
              through the catalog, not a verb)
   and the launcher statics — served from here (not file://) so the webview is
   same-origin with the app API and its click POSTs need no CORS. Whitelisted
   prefixes only; \"../\" is refused."
  (:require [clojure.string  :as str]
            [clojure.java.io :as io]
            [ujima.desktop.app      :as app]
            [ujima.desktop.http.app :as apps]
            [ujima.desktop.http.ui  :as ui]))


;; this tier's ex-info vocabulary -> status, merged into the edge at init
(def error-status
  {:app/unknown-app 404
   :app/bad-url     400})


;; --- file serving: statics + the app icon --------------------------------

(def ^:private static-prefixes #{"launcher" "icons" "wall.png" "wall.svg"})
(def ^:private content-types
  {"html" "text/html; charset=utf-8" "css" "text/css" "js" "text/javascript"
   "svg" "image/svg+xml" "png" "image/png" "json" "application/json"})

(defn- static-file
  "GET /launcher/** , /icons/** , or /wall.{png,svg} -> the file under `root`.
   /launcher -> index.html. nil when it is not a static path, escapes root,
   or is not a file."
  [root parts]
  (let [parts (if (= parts ["launcher"]) ["launcher" "index.html"] parts)]
    (when (and (seq parts) (static-prefixes (first parts)) (not (some #{".."} parts)))
      (let [f   (io/file root (str/join "/" parts))
            ext (some-> (re-find #"\.([^.]+)$" (.getName f)) second str/lower-case)]
        (when (.isFile f)
          {:status 200
           :headers {"content-type" (get content-types ext "application/octet-stream")}
           :body f})))))

(defn- icon-file
  "GET /app/icon/<id> -> the catalog-resolved icon, so the launcher never
   touches the filesystem layout."
  [id]
  (let [f (some-> (app/icon-path (keyword id)) io/file)]
    (if (and f (.isFile f))
      {:status 200 :headers {"content-type" "image/svg+xml"} :body f}
      {:status 404 :body {:error "unknown app"}})))


;; --- the tier handler ----------------------------------------------------

(defn handler
  "The shell tier for the edge's :handlers, closed over :static-root."
  [{:keys [static-root] :or {static-root "/ujima/desktop/shell"}}]
  (fn [req parts body]
    (case [(:request-method req) parts]
      [:get  ["ui" "state"]]                    (ui/stream req)
      [:get  ["ui" "apps"]]                     (apps/stream req)
      [:get  ["ui" "keyboard" "layout" "next"]] {:status 200 :body (ui/keyboard-next)}
      [:post ["ui" "volume" "move"]]            (do (ui/volume-moved! (:value body)) {:status 202 :body {}})
      [:get  ["app" "catalog"]]                 {:status 200 :body {:apps (app/catalog-listing)}}
      [:post ["app" "run"]]                     (do (app/run! (keyword (:app-id body)))       {:status 202 :body {}})
      [:post ["app" "switch"]]                  (do (app/switch-to! (keyword (:app-id body))) {:status 202 :body {}})
      [:post ["app" "open-url"]]                (do (app/open-url! (:url body))               {:status 202 :body {}})
      [:post ["app" "close"]]                   (do (app/close-focused!)                      {:status 202 :body {}})
      [:post ["app" "home"]]                    (do (app/go-home!)                            {:status 202 :body {}})
      [:post ["app" "next"]]                    (do (app/cycle! 1)                            {:status 202 :body {}})
      [:post ["app" "prev"]]                    (do (app/cycle! -1)                           {:status 202 :body {}})
      (when (= :get (:request-method req))
        (if (and (= 3 (count parts)) (= ["app" "icon"] (subvec parts 0 2)))
          (icon-file (nth parts 2))
          (static-file static-root parts))))))
