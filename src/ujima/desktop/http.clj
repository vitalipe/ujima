(ns ujima.desktop.http
  "The desktop's loopback HTTP API (http-kit). Transport only — parse the
   request, run at most ONE command, respond with a query (mechanical
   command-then-query stitching; no domain logic lives here). Three tiers:
     /api/**  the settings resource API (commands + queries; the future console
              reuses them) — writes respond with the fresh resource
     /ui/**   the GUI edge (ujima.desktop.ui): the NDJSON state + apps streams
              and the verbs where interaction ≠ state (throttled volume moves)
     /app/**  the app layer (ujima.desktop.app): the catalog now, start/stop/
              focus when the startup slice lands."
  (:require [clojure.string     :as str]
            [org.httpkit.server :as http]
            [lib.edn            :refer [edn->json json->edn]]
            [ujima.log          :as log]
            [ujima.control.commands :as commands]
            [ujima.control.queries  :as queries]
            [ujima.desktop.app      :as app]
            [ujima.desktop.ui       :as ui]))


(defn- json [status body]
  {:status status :headers {"content-type" "application/json"} :body (edn->json body)})


;; ex-info {:error <kw>} -> status; anything unmapped is a real bug -> 500.
(def ^:private error-status
  {:request/malformed        400
   :audio/no-output          409
   :keyboard/unknown-layout  409})


(defn route
  "Pure: [method uri] -> verb keyword, nil when unrouted (trailing slashes ok)."
  [method uri]
  (let [parts (->> (str/split (str uri) #"/") (remove str/blank?) vec)]
    (get {[:get  ["api" "audio"]]                     :audio/status
          [:get  ["api" "input" "keyboard"]]          :keyboard/status
          [:post ["api" "audio" "volume"]]            :audio/volume
          [:post ["api" "audio" "mute"]]              :audio/mute
          [:post ["api" "audio" "output"]]            :audio/output
          [:post ["api" "input" "keyboard" "layout"]] :keyboard/layout
          [:get  ["ui" "state"]]                      :ui/state
          [:get  ["ui" "apps"]]                       :ui/apps
          [:post ["ui" "volume" "move"]]              :ui/volume
          [:get  ["app" "catalog"]]                   :app/catalog}
         [method parts])))


(defn- handler [req]
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
        :ui/apps             (ui/apps-stream req)
        :ui/volume           (do (ui/volume-moved! (:value body)) (json 202 {}))
        :app/catalog         (json 200 {:apps (app/catalog-listing)})
        (json 404 {:error "not found"})))
    (catch clojure.lang.ExceptionInfo e
      (if-let [status (error-status (:error (ex-data e)))]
        (do (log/warn "desktop http: rejected" {:uri (:uri req) :error (ex-message e)})
            (json status {:error (ex-message e)}))
        (do (log/error "desktop http: handler failed" {:uri (:uri req) :error (ex-message e)})
            (json 500 {:error "internal error"}))))
    (catch Throwable e
      (log/error "desktop http: handler failed" {:uri (:uri req) :error (ex-message e)})
      (json 500 {:error "internal error"}))))


(defn start!
  "Start the loopback API; returns http-kit's stop fn. A taken port throws — the
   session dies loudly and systemd rebuilds it."
  [{:keys [host port] :or {host "127.0.0.1" port 1337}}]
  (log/info "desktop http listening" {:host host :port port})
  (http/run-server handler {:ip host :port port}))
