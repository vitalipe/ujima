(ns ujima.desktop.http
  "The desktop's loopback HTTP API (http-kit). Transport only — parse the request,
   call ONE verb, serialize its result; no domain logic lives here. Two tiers:
     /api/**    the settings resource API (ujima.control.commands; the future
                console reuses these verbs) — writes return the fresh resource
     /shell/**  interaction verbs where interaction ≠ state (ujima.desktop.shell):
                today only the throttled fire-and-forget volume moves."
  (:require [clojure.string     :as str]
            [org.httpkit.server :as http]
            [lib.edn            :refer [edn->json json->edn]]
            [ujima.log          :as log]
            [ujima.control.commands :as commands]
            [ujima.desktop.shell    :as shell]))


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
    (get {[:get  ["api" "audio"]]                    :audio/status
          [:get  ["api" "input" "keyboard"]]         :keyboard/status
          [:post ["api" "audio" "volume"]]           :audio/set-volume
          [:post ["api" "audio" "mute"]]             :audio/set-mute
          [:post ["api" "input" "keyboard" "layout"]] :keyboard/set-layout
          [:post ["shell" "volume" "move"]]          :shell/volume-move}
         [method parts])))


(defn- handler [req]
  (try
    (let [verb (route (:request-method req) (:uri req))
          body (when (= :post (:request-method req)) (json->edn (:body req)))]
      (case verb
        :audio/status        (json 200 (commands/audio-status))
        :keyboard/status     (json 200 (commands/keyboard-status))
        :audio/set-volume    (json 200 (commands/set-volume! (:value body)))
        :audio/set-mute      (json 200 (commands/set-mute! (:muted body)))
        :keyboard/set-layout (json 200 (commands/set-layout! (:layout body)))
        :shell/volume-move   (do (shell/volume-moved! (:value body)) (json 202 {}))
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
