(ns ujima.desktop.http-test
  "The shell module's file serving: the shell tree is the only thing served,
   and containment is checked after the path resolves."
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs  :as fs]
            [lib.http     :as http]
            [ujima.desktop.http :as shell]))


(defn- tree []
  (let [root (str (fs/create-temp-dir))]
    (fs/create-dirs (str root "/launcher"))
    (spit (str root "/launcher/index.html") "<h1>launcher</h1>")
    (spit (str root "/wall.png") "PNGDATA")
    (spit (str root "/../ESCAPED") "not under the root")
    ;; a way out that carries no ".." in the url at all
    (fs/create-sym-link (str root "/launcher/leak") (str root "/../ESCAPED"))
    root))


(defn- GET [root uri]
  (with-redefs [shell/static-root root]
    ((http/app {:endpoints {"" shell/endpoints} :log (fn [& _])})
     {:request-method :get :uri uri})))


(deftest the-shell-tree-is-served
  (let [root (tree)]
    (is (= 200 (:status (GET root "/launcher/index.html"))))
    (is (= 200 (:status (GET root "/launcher/"))) "an empty tail is index.html")
    (is (= 200 (:status (GET root "/wall.png"))))
    (is (= 404 (:status (GET root "/launcher/nope.html"))))))


(deftest nothing-outside-it-is
  (let [root (tree)]
    (is (= 404 (:status (GET root "/launcher/../ESCAPED"))) "climbing out")
    (is (= 404 (:status (GET root "/icons/../launcher/index.html")))
        "even climbing out and back to a file that IS servable")
    (is (= 404 (:status (GET root "/launcher/leak")))
        "a symlink out of the tree — no .. in the url, so only containment catches it")))
