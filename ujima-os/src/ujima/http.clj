(ns ujima.http
  (:require [clojure.string :as str]
            
            [org.httpkit.server :as http]
            
            [ujima.log :as log]

            [ujima.target           :refer [->runtime]]
            [ujima.runtime.protocol :as runtime]
            [ujima.runtime.settings :as settings]


            [ujima.io.fs :refer [slurp-edn!]]
            [ujima.edn   :refer [edn->json json->edn]]

            [ujima.agent :as ujima-agent]))


(defn response
  ([status body]
   {:status status
    :headers {"content-type" "application/json"}
    :body (edn->json body)}))


(defn ok [body]
  (response 200 {:ok? true :data body}))


(defn bad-request [message data]
  (response 400 {:ok? false :error {:message message :data data}}))


(defn not-found []
  (response 404 {:ok? false :error {:message "Not found"}}))


(defn server-error [e]
  (log/error "HTTP handler failed" {:error (ex-message e)})
  (response 500 {:ok? false
                 :error {:message "Internal server error"}}))


(defn handle-system-get [runtime* resource]
  (case resource
    "hostname"         (ok {:hostname (runtime/hostname runtime*)})
    "timezone"         (ok {:timezone (runtime/timezone runtime*)})
    "keyboard-layouts" (ok {:keyboard-layouts (runtime/keyboard-layouts runtime*)})
    
    (not-found)))


(defn handle-system-post [runtime* resource request]
  (let [body (json->edn (:body request))]
    (case resource
      "hostname"         (ok (settings/hostname+settings! runtime* (:hostname body)))
      "timezone"         (ok (settings/timezone+settings! runtime* (:timezone body)))
      "keyboard-layouts" (ok (settings/keyboard-layouts+settings! runtime* (:keyboard-layouts body)))
      "reboot"           (ok (runtime/reboot! runtime*))
      "shutdown"         (ok (runtime/shutdown! runtime*))
      
      (not-found))))


(defn handle-desktop-get [runtime* resource]
  (case resource
    "volume"        (ok {:volume (runtime/volume runtime*)})
    "wallpaper"     (ok {:wallpaper (runtime/wallpaper runtime*)})
    "screen-locked" (ok {:screen-locked (runtime/screen-locked? runtime*)})
    "apps"          (ok (runtime/app-list runtime*))
    
    (not-found)))


(defn handle-desktop-post [runtime* resource request]
  (let [body (json->edn (:body request))]
    (case resource
      "volume"          (ok (runtime/volume! runtime* (:volume body)))
      "wallpaper"       (ok (settings/wallpaper+settings! runtime* (:wallpaper body)))
      "screen-locked"   (if (:screen-locked body) 
                          (ok (runtime/screen-lock! runtime*))
                          (ok (runtime/screen-unlock! runtime*)))

      "app-start"       (ok (runtime/app-start! runtime* (:name body) (:args body)))
      "app-kill"        (ok (runtime/app-kill! runtime* (:name body)))
      
      (not-found))))


(defn handle-runtime-get [runtime* resource]
  (case resource
    "settings"      (ok {:settings (runtime/settings runtime*)})
    "control-token" (ok {:token (runtime/probe-control-token runtime*)})
    
    (not-found)))


(defn handle-runtime-post [runtime* resource request]
  (let [body (json->edn (:body request))]
    (case resource
      "settings" (ok (runtime/settings! runtime* (:settings body)))
      
      (not-found))))


(defn handler [runtime* request]
  (try
    (let [method (:request-method request)
          parts (-> (:uri request)
                    (str/replace #"^/api/" "")
                    (str/split #"/"))
          [scope group resource] parts]
      (if-not (= scope "runtime")
        (not-found)
        (case group

          "system"
          (case method
            :get (handle-system-get runtime* resource)
            :post (handle-system-post runtime* resource request)
            (bad-request "Unsupported method" {:method method}))

          "desktop"
          (case method
            :get (handle-desktop-get runtime* resource)
            :post (handle-desktop-post runtime* resource request)
            (bad-request "Unsupported method" {:method method}))

          nil
          (case method
            :get (handle-runtime-get runtime* nil)
            (bad-request "Unsupported method" {:method method}))

          (case method
            :get (handle-runtime-get runtime* group)
            :post (handle-runtime-post runtime* group request)
            (bad-request "Unsupported method" {:method method})))))

    (catch Throwable e
      (server-error e))))


(defn start! [env runtime*]
  (let [host (get-in env [:http :host] "0.0.0.0")
        port (get-in env [:http :port] 1337)]

    (log/info "Starting HTTP server" {:host host :port port})
    
    (http/run-server
      (partial handler runtime*)
      {:ip host
       :port port})))


(defn -main [& args]
  (let [[env-path] args
        env      (slurp-edn! env-path {})]
        
    ;; first set log level
    (log/set-log-level! (get-in env [:log :level] :info))    
    
    (let [runtime* (->runtime env)]
      (ujima-agent/init! (get env :agent {}) runtime*)
      (start! env runtime*))

    ;; block
    @(promise)))