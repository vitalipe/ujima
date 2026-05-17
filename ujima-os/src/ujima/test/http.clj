(ns ujima.test.http
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [ujima.io.fs :refer [slurp-edn!]]))


(defn ->base-url [env]
  (let [host (get-in env [:http :host] "localhost")
        port (get-in env [:http :port] 1337)
        host (if (= host "0.0.0.0") "localhost" host)]
    (str "http://" host ":" port)))


(defn parse-json [text]
  (json/parse-string text true))


(defn get-json! [base-url path]
  (-> (http/get (str base-url path))
      :body
      parse-json))


(defn post-json! [base-url path body]
  (-> (http/post (str base-url path)
        {:headers {"content-type" "application/json"}
         :body (json/generate-string body)})
      :body
      parse-json))

(defn test! [name f]
  (try
    (print (str "TEST " name " ... "))
    (flush)
    (f)
    (println "OK")
    (catch Throwable e
      (println "FAIL")
      (println " " (ex-message e)))))

(defn run! [env]
  (let [base-url (->base-url env)]
    (test! "system hostname"
      #(get-json! base-url "/api/runtime/system/hostname"))

    (test! "system timezone"
      #(get-json! base-url "/api/runtime/system/timezone"))

    (test! "system keyboard layouts"
      #(get-json! base-url "/api/runtime/system/keyboard-layouts"))

    (test! "desktop volume get"
      #(get-json! base-url "/api/runtime/desktop/volume"))

    (test! "desktop volume set"
      #(post-json! base-url "/api/runtime/desktop/volume" {:volume 60}))

    (test! "desktop screen locked"
      #(get-json! base-url "/api/runtime/desktop/screen-locked"))

    (test! "runtime control token"
      #(get-json! base-url "/api/runtime/control-token"))

    (test! "runtime settings"
      #(get-json! base-url "/api/runtime/settings"))))


(defn -main [& args]
  (let [[env-path] args
        env (slurp-edn! env-path {})]

    (run! env)))