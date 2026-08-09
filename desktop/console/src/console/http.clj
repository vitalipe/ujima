(ns console.http
  "The console's HTTP edge (http-kit). Transport only, desktop.http-style:
     POST /circle/<verb>       circle's command tier -> 202 {:job id}
     POST /setup/<op>          setup's commands (jobs) + panel ops (sync)
     GET  /ui/circle, /ui/setup   the composed views the panels poll
     GET  /console/job[/<id>]  the shared job inspection tier
   plus statics: / the chooser, /circle/* and /setup/* the panels."
  (:require [clojure.string     :as str]
            [clojure.java.io    :as io]
            [org.httpkit.server :as http]
            [lib.edn            :refer [edn->json json->edn]]
            [console.jobs       :as jobs]
            [console.circle     :as circle]
            [console.setup      :as setup]))


(defn- json [status body]
  {:status status :headers {"content-type" "application/json"} :body (edn->json body)})

(defn- body-edn
  "The request body as edn; a body that isn't JSON is the caller's fault, not a 500."
  [req]
  (try (json->edn (:body req))
       (catch Exception _
         (throw (ex-info "body must be JSON" {:error :request/malformed})))))


(def ^:private content-types {"html" "text/html; charset=utf-8"
                              "css"  "text/css"
                              "js"   "text/javascript"})

(def ^:private ui-files
  {"circle" #{"index.html" "circle.css" "circle.js"}
   "setup"  #{"index.html" "setup.css" "setup.js"}})

(defn- serve-file [root name]
  (let [f   (io/file root name)
        ext (some-> (re-find #"\.([^.]+)$" name) second)]
    (when (.isFile f)
      {:status  200
       :headers {"content-type" (content-types ext "application/octet-stream")}
       :body    f})))

(defn- static-file
  "/ -> the chooser; /circle[/<file>] and /setup[/<file>] -> that panel's
   files; whitelist only, so no escapes."
  [ui-roots parts]
  (cond
    (empty? parts)
    (serve-file (:console ui-roots) "index.html")

    (contains? ui-files (first parts))
    (let [[app file] parts
          name       (or file "index.html")]
      (when (and (<= (count parts) 2) ((ui-files app) name))
        (serve-file (get ui-roots (keyword app)) name)))))


(defn- setup-route [transport op body]
  (case op
    "settings" (json 202 {:job (setup/settings-job! transport body)})
    "clock"    (json 202 {:job (setup/clock-job! transport body)})
    "checks"   (json 202 {:job (setup/checks-job! transport body)})
    "restart"  (json 202 {:job (setup/power-job! transport :restart body)})
    "poweroff" (json 202 {:job (setup/power-job! transport :poweroff body)})
    "remove"   (json 200 (setup/remove! transport body))
    "rescan"   (json 200 (setup/rescan! transport))
    nil))

(defn- handler [{:keys [ui-roots transport]} req]
  (try
    (let [method (:request-method req)
          parts  (->> (str/split (str (:uri req)) #"/") (remove str/blank?) vec)]
      (cond
        (and (= :post method) (circle/verb-routes parts))
        (let [verb (circle/verb-routes parts)
              body (body-edn req)]
          (json 202 {:job (circle/act! transport verb body)}))

        (and (= :post method) (= 2 (count parts)) (= "setup" (first parts)))
        (or (setup-route transport (second parts) (body-edn req))
            (json 404 {:error "not found"}))

        (and (= :get method) (= ["ui" "circle"] parts))
        (json 200 (circle/view transport))

        (and (= :get method) (= ["ui" "setup"] parts))
        (json 200 (setup/view transport))

        (and (= :get method) (= ["console" "job"] parts))
        (json 200 {:jobs (jobs/jobs)})

        (and (= :get method) (= 3 (count parts)) (= ["console" "job"] (subvec parts 0 2)))
        (if-let [job (some-> (nth parts 2) parse-long jobs/job)]
          (json 200 job)
          (json 404 {:error "unknown job"}))

        (= :get method)
        (or (static-file ui-roots parts)
            (json 404 {:error "not found"}))

        :otherwise
        (json 404 {:error "not found"})))
    (catch clojure.lang.ExceptionInfo e
      (if (= :request/malformed (:error (ex-data e)))
        (json 400 {:error (ex-message e)})
        (do (println "console http: handler failed:" (ex-message e))
            (json 500 {:error "internal error"}))))
    (catch Throwable e
      (println "console http: handler failed:" (ex-message e))
      (json 500 {:error "internal error"}))))


(defn init!
  "Starts the server; returns http-kit's stop fn. A taken port throws."
  [{:keys [host port ui-roots transport] :or {host "127.0.0.1" port 1338}}]
  (http/run-server (partial handler {:ui-roots ui-roots :transport transport})
                   {:ip host :port port}))
