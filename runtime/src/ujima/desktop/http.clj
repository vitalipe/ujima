(ns ujima.desktop.http
  "The desktop's loopback HTTP API (http-kit). Transport only — parse the
   request, run at most ONE command, respond with a query (mechanical
   command-then-query stitching; no domain logic lives here). Three tiers:
     /api/**  the settings resource API (commands + queries; the future console
              reuses them) — writes respond with the fresh resource
     /ui/**   the GUI edge (desktop.http.ui settings, desktop.http.app apps):
              the NDJSON streams and the verbs where interaction ≠ state
              (throttled volume moves)
     /app/**  the app layer (ujima.desktop.app): the catalog now, start/stop/
              focus when the startup slice lands."
  (:require [clojure.string     :as str]
            [clojure.java.io    :as io]
            [org.httpkit.server :as http]
            [lib.edn            :refer [edn->json json->edn]]
            [ujima.log          :as log]
            [ujima.control.commands :as commands]
            [ujima.control.queries  :as queries]
            [ujima.desktop.app      :as app]
            [ujima.desktop.http.app :as apps]
            [ujima.desktop.http.ui  :as ui]))


(defn- json [status body]
  {:status status :headers {"content-type" "application/json"} :body (edn->json body)})


;; Static assets for the webview launcher: it is served from here (not file://) so it is
;; same-origin with the app API and its click POSTs / future pulls need no CORS. Only the
;; launcher + shared icon dirs and the wallpaper (drawn by the launcher too) are exposed,
;; and "../" is refused.
(def ^:private static-prefixes #{"launcher" "icons" "wall.png" "wall.svg"})
(def ^:private content-types
  {"html" "text/html; charset=utf-8" "css" "text/css" "js" "text/javascript"
   "svg" "image/svg+xml" "png" "image/png" "json" "application/json"})

(defn- static-file
  "GET /launcher/** , /icons/** , or /wall.{png,svg} -> the file under `root`. /launcher ->
   index.html. nil when it is not a static path, escapes root, or is not a file."
  [root uri]
  (let [parts (->> (str/split (str uri) #"/") (remove str/blank?) vec)
        parts (if (= parts ["launcher"]) ["launcher" "index.html"] parts)]
    (when (and (seq parts) (static-prefixes (first parts)) (not (some #{".."} parts)))
      (let [f   (io/file root (str/join "/" parts))
            ext (some-> (re-find #"\.([^.]+)$" (.getName f)) second str/lower-case)]
        (when (.isFile f)
          {:status 200
           :headers {"content-type" (get content-types ext "application/octet-stream")}
           :body f})))))


;; ex-info {:error <kw>} -> status; anything unmapped is a real bug -> 500.
(def ^:private error-status
  {:request/malformed        400
   :audio/no-output          409
   :keyboard/unknown-layout  409
   :app/unknown-app          404
   :app/bad-url              400})


(defn route
  "Pure: [method uri] -> verb keyword, nil when unrouted (trailing slashes ok).
   /app/icon/<id> is the one parametrized route — the handler re-reads the id segment."
  [method uri]
  (let [parts (->> (str/split (str uri) #"/") (remove str/blank?) vec)]
    (or (when (and (= :get method) (= 3 (count parts)) (= ["app" "icon"] (subvec parts 0 2)))
          :app/icon)
        (get {[:get  ["api" "audio"]]                     :audio/status
          [:get  ["api" "input" "keyboard"]]          :keyboard/status
          [:post ["api" "audio" "volume"]]            :audio/volume
          [:post ["api" "audio" "mute"]]              :audio/mute
          [:post ["api" "audio" "output"]]            :audio/output
          [:post ["api" "input" "keyboard" "layout"]] :keyboard/layout
          [:get  ["ui" "state"]]                      :ui/state
          [:get  ["ui" "apps"]]                       :ui/apps
          [:get  ["ui" "keyboard" "layout" "next"]]   :ui/keyboard-next
          [:post ["ui" "volume" "move"]]              :ui/volume
          [:get  ["app" "catalog"]]                   :app/catalog
          [:post ["app" "run"]]                       :app/run
          [:post ["app" "switch"]]                    :app/switch
          [:post ["app" "open-url"]]                  :app/open-url
          [:post ["app" "close"]]                     :app/close
          [:post ["app" "home"]]                      :app/home
          [:post ["app" "next"]]                      :app/next
          [:post ["app" "prev"]]                      :app/prev}
             [method parts]))))


(defn- handler [static-root req]
  (try
    (let [verb (route (:request-method req) (:uri req))
          body (when (= :post (:request-method req)) (json->edn (:body req)))]
      (case verb
        :audio/status        (json 200 (queries/audio-status))
        :keyboard/status     (json 200 (queries/keyboard-status))
        :audio/volume        (do (commands/change-current-volume! (:value body))
                                 (json 200 (queries/audio-status)))
        :audio/mute          (do (commands/change-mute! (:muted body))
                                 (json 200 (queries/audio-status)))
        :audio/output        (do (commands/change-active-output! (:output body))
                                 (json 200 (queries/audio-status)))
        :keyboard/layout     (do (commands/change-keyboard-layout! (:layout body))
                                 (json 200 (queries/keyboard-status)))
        :ui/state            (ui/stream req)
        :ui/apps             (apps/stream req)
        :ui/keyboard-next    (json 200 (ui/keyboard-next))
        :ui/volume           (do (ui/volume-moved! (:value body)) (json 202 {}))
        :app/catalog         (json 200 {:apps (app/catalog-listing)})
        :app/icon            (let [id   (-> (->> (str/split (str (:uri req)) #"/")
                                                 (remove str/blank?) vec)
                                            (nth 2) keyword)
                                    path (app/icon-path id)
                                    f    (some-> path io/file)]
                                (if (and f (.isFile f))
                                  {:status 200
                                   :headers {"content-type" "image/svg+xml"}
                                   :body f}
                                  (json 404 {:error "unknown app"})))
        :app/run             (do (app/run! (keyword (:app-id body)))
                                 (json 202 {}))
        :app/switch          (do (app/switch-to! (keyword (:app-id body)))
                                 (json 202 {}))
        :app/open-url        (do (app/open-url! (:url body))
                                 (json 202 {}))
        :app/close           (do (app/close-focused!)
                                 (json 202 {}))
        :app/home            (do (app/go-home!)
                                 (json 202 {}))
        :app/next            (do (app/cycle! 1)
                                 (json 202 {}))
        :app/prev            (do (app/cycle! -1)
                                 (json 202 {}))
        (or (when (= :get (:request-method req)) (static-file static-root (:uri req)))
            (json 404 {:error "not found"}))))
    (catch clojure.lang.ExceptionInfo e
      (if-let [status (error-status (:error (ex-data e)))]
        (do (log/warn "desktop http: rejected" {:uri (:uri req) :error (ex-message e)})
            (json status {:error (ex-message e)}))
        (do (log/error "desktop http: handler failed" {:uri (:uri req) :error (ex-message e)})
            (json 500 {:error "internal error"}))))
    (catch Throwable e
      (log/error "desktop http: handler failed" {:uri (:uri req) :error (ex-message e)})
      (json 500 {:error "internal error"}))))


(defn init!
  "Start the loopback API (+ static launcher assets from static-root); returns http-kit's stop
   fn. A taken port throws — the session dies loudly and systemd rebuilds it."
  [{:keys [host port static-root]
    :or   {host "127.0.0.1" port 1337 static-root "/ujima/desktop/shell"}}]
  (log/info "desktop http listening" {:host host :port port :static static-root})
  (http/run-server (partial handler static-root) {:ip host :port port}))
