(ns ujima.desktop.http.files
  "What the shell answers with a file: its own tree, containment-checked, and
   the app icons, which are catalog-resolved and live elsewhere."
  (:require [clojure.string  :as str]
            [clojure.java.io :as io]
            [ujima.desktop.app :as app]))


(def ^:private static-root "/ujima/desktop/shell")

(def ^:private content-types
  {"html" "text/html; charset=utf-8" "css" "text/css" "js" "text/javascript"
   "svg" "image/svg+xml" "png" "image/png" "json" "application/json"})


;; --- file serving: statics + the app icon --------------------------------

(defn- serve
  "A file from the shell tree, or nil — containment checked after resolution."
  [f]
  (let [root (str (.getCanonicalPath (io/file static-root)) "/")
        ext  (some-> (re-find #"\.([^.]+)$" (.getName f)) second str/lower-case)]
    (when (and (.isFile f) (str/starts-with? (.getCanonicalPath f) root))
      {:status 200
       :headers {"content-type" (get content-types ext "application/octet-stream")}
       :body f})))

(defn static-file
  "TAIL under DIR; an empty tail is that directory's index.html."
  [dir tail]
  (serve (io/file static-root dir (if (str/blank? tail) "index.html" tail))))

(defn icon-file
  "The catalog-resolved icon, so the launcher never sees the layout."
  [id]
  (let [f (some-> (app/icon-path (keyword id)) io/file)]
    (if (and f (.isFile f))
      {:status 200 :headers {"content-type" "image/svg+xml"} :body f}
      {:status 404 :body {:error "unknown app"}})))


(defn wall [name] (serve (io/file static-root name)))
