(ns circle.http
  "Circle's HTTP edge (http-kit). Transport only, desktop.http-style:
     POST /circle/<verb>     the command tier -> 202 {:job id}
     GET  /circle/job[/<id>] the inspection tier (debug / future CLI)
     GET  /ui/circle         the one composed view the panel polls
   plus the panel's static files at /."
  (:require [clojure.string     :as str]
            [clojure.java.io    :as io]
            [org.httpkit.server :as http]
            [lib.edn            :refer [edn->json json->edn]]
            [circle.fleet       :as fleet]))


(defn- json [status body]
  {:status status :headers {"content-type" "application/json"} :body (edn->json body)})

(defn- malformed! [message]
  (throw (ex-info message {:error :request/malformed})))


(def ^:private verb-routes
  {["circle" "mute"]      :mute
   ["circle" "unmute"]    :unmute
   ["circle" "volume"]    :volume
   ["circle" "close-app"] :close-app
   ["circle" "open-app"]  :open-app
   ["circle" "open-url"]  :open-url
   ["circle" "lock"]      :lock
   ["circle" "unlock"]    :unlock
   ["circle" "restart"]   :restart
   ["circle" "poweroff"]  :poweroff})

(defn- targets [body]
  (let [ts (:targets body)]
    (when (or (not (sequential? ts)) (empty? ts) (not-every? string? ts))
      (malformed! "targets must be a non-empty list of peer ids"))
    (vec ts)))

(defn- verb-args [verb body]
  (case verb
    :open-app (if (string? (:app body))
                {:app (:app body)}
                (malformed! "open-app needs an :app id"))
    :open-url (if (and (string? (:url body)) (seq (:url body)))
                {:url (:url body)}
                (malformed! "open-url needs a :url"))
    :volume   (if (number? (:value body))
                {:value (-> (:value body) double Math/round (max 0) (min 100))}
                (malformed! "volume needs a :value 0-100"))
    {}))


(def ^:private ui-files      #{"index.html" "circle.css" "circle.js"})
(def ^:private content-types {"html" "text/html; charset=utf-8"
                              "css"  "text/css"
                              "js"   "text/javascript"})

(defn- static-file
  "GET / or /<file> -> the file under ui-root; whitelist only, so no escapes."
  [root uri]
  (let [name (or (->> (str/split (str uri) #"/") (remove str/blank?) first)
                 "index.html")]
    (when (ui-files name)
      (let [f   (io/file root name)
            ext (some-> (re-find #"\.([^.]+)$" name) second)]
        (when (.isFile f)
          {:status  200
           :headers {"content-type" (content-types ext "application/octet-stream")}
           :body    f})))))


(defn- handler [{:keys [ui-root transport]} req]
  (try
    (let [method (:request-method req)
          parts  (->> (str/split (str (:uri req)) #"/") (remove str/blank?) vec)]
      (cond
        (and (= :post method) (verb-routes parts))
        (let [verb (verb-routes parts)
              body (json->edn (:body req))]
          (json 202 {:job (fleet/act! transport verb (targets body) (verb-args verb body))}))

        (and (= :get method) (= ["ui" "circle"] parts))
        (json 200 {:schema 1
                   :self   ((:self transport))
                   :peers  ((:peers transport))
                   :panel  {:action (fleet/active-action)}})

        (and (= :get method) (= ["circle" "job"] parts))
        (json 200 {:jobs (fleet/jobs)})

        (and (= :get method) (= 3 (count parts)) (= ["circle" "job"] (subvec parts 0 2)))
        (if-let [job (some-> (nth parts 2) parse-long fleet/job)]
          (json 200 job)
          (json 404 {:error "unknown job"}))

        (= :get method)
        (or (static-file ui-root (:uri req))
            (json 404 {:error "not found"}))

        :otherwise
        (json 404 {:error "not found"})))
    (catch clojure.lang.ExceptionInfo e
      (if (= :request/malformed (:error (ex-data e)))
        (json 400 {:error (ex-message e)})
        (do (println "circle http: handler failed:" (ex-message e))
            (json 500 {:error "internal error"}))))
    (catch Throwable e
      (println "circle http: handler failed:" (ex-message e))
      (json 500 {:error "internal error"}))))


(defn init!
  "Starts the server; returns http-kit's stop fn. A taken port throws."
  [{:keys [host port ui-root transport] :or {host "127.0.0.1" port 1338}}]
  (http/run-server (partial handler {:ui-root ui-root :transport transport})
                   {:ip host :port port}))
