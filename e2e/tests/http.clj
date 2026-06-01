(ns e2e.tests.http
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]))


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


(defn server-up? [url]
  (try
    (let [res (http/head url {:throw false
                              :timeout 2000})]
      (<= 200 (:status res) 399))
    (catch Exception _
      false)))


(defn test! [name f]
  (try

    (print (str "TEST " name " ... "))
    (flush)

    (let [result (f)]
      (if (zero? (:exit result))
        (do 
          (println "OK")
          true)
        
        (do
          (println "FAIL")
          (println " " (:err result))
          false)))
   
    (catch Throwable e
      (println "FAIL")
      (println " " (ex-message e))
      false)))


(defn run! [env]
  (let [base-url (->base-url env)]
    
    (if-not (server-up? base-url)
      (do
        (println)
        (println "Not running HTTP e2e tests! server is down at:" base-url)
        false)

      ;; server up, run tests
      (every? true?
        [ (test! "system hostname"
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
            #(get-json! base-url "/api/runtime/settings"))]))))
