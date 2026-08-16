(ns ujima.desktop.http-test
  "The shell module: its file serving (the shell tree is the only thing served,
   and containment is checked after the path resolves) and its /ui verbs."
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs  :as fs]
            [lib.edn      :refer [edn->json]]
            [lib.http     :as http]
            [ujima.control.commands :as effects]
            [ujima.desktop.app      :as app]
            [ujima.desktop.http     :as shell]))


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
  ((http/app {:endpoints {"ujima-desktop" (shell/endpoints {:static-root root})} :log (fn [& _])})
   {:request-method :get :uri uri}))

(defn- POST
  ([uri] (POST uri nil))
  ([uri body]
   ((http/app {:endpoints {"ujima-desktop" (shell/endpoints {:static-root "/nowhere"})} :log (fn [& _])})
    (cond-> {:request-method :post :uri uri}
      body (assoc :body (edn->json body))))))


(deftest the-shell-tree-is-served
  (let [root (tree)]
    (is (= 200 (:status (GET root "/ujima-desktop/assets/launcher/index.html"))))
    (is (= 200 (:status (GET root "/ujima-desktop/assets/launcher/"))) "an empty tail is index.html")
    (is (= 200 (:status (GET root "/ujima-desktop/assets/wall.png"))))
    (is (= 404 (:status (GET root "/ujima-desktop/assets/launcher/nope.html"))))))


(deftest nothing-outside-it-is
  (let [root (tree)]
    (is (= 404 (:status (GET root "/ujima-desktop/assets/launcher/../ESCAPED"))) "climbing out")
    (is (= 404 (:status (GET root "/ujima-desktop/assets/icons/../launcher/index.html")))
        "even climbing out and back to a file that IS servable")
    (is (= 404 (:status (GET root "/ujima-desktop/assets/launcher/leak")))
        "a symlink out of the tree — no .. in the url, so only containment catches it")))


(deftest the-command-verbs-reach-the-same-effects-api-does
  (let [seen (atom [])]
    (with-redefs [app/run!           (fn [id]  (swap! seen conj [:open id]))
                  app/close-focused! (fn []    (swap! seen conj [:close]))
                  app/go-home!       (fn []    (swap! seen conj [:home]))
                  app/open-url!      (fn [url] (swap! seen conj [:url url]))
                  app/cycle!         (fn [n]   (swap! seen conj [:cycle n]))]
      (is (every? #(= 202 (:status %))
                  [(POST "/ujima-desktop/commands/app/open" {:app "files"})
                   (POST "/ujima-desktop/commands/app/close")
                   (POST "/ujima-desktop/commands/app/home")
                   (POST "/ujima-desktop/commands/app/open-url" {:url "https://ujima.lan"})
                   (POST "/ujima-desktop/commands/app/next")
                   (POST "/ujima-desktop/commands/app/prev")]))
      (is (= [[:open :files] [:close] [:home] [:url "https://ujima.lan"] [:cycle 1] [:cycle -1]]
             @seen)))))


(deftest a-command-never-names-a-scope
  (let [seen (atom nil)]
    (with-redefs [effects/change-keyboard-layout! (fn [code scope] (reset! seen [code scope]))]
      (is (= 202 (:status (POST "/ujima-desktop/commands/keyboard/layout" {:layout "il"}))))
      (is (= ["il" :session] @seen) "the tier IS the session; the caller has no scope to get wrong"))
    (with-redefs [effects/change-setting! (fn [path value scope] (reset! seen [path value scope]))]
      (is (= 202 (:status (POST "/ujima-desktop/commands/audio/muted" {:value true}))))
      (is (= [[:audio :muted] true :session] @seen)
          "a named verb, not the generic settings write"))))


(deftest a-command-still-checks-its-params
  (is (= 400 (:status (POST "/ujima-desktop/commands/app/open" {}))))
  (is (= 400 (:status (POST "/ujima-desktop/commands/app/open-url" {:url ""}))))
  (is (= 400 (:status (POST "/ujima-desktop/commands/audio/muted" {:value "maybe"})))))


(deftest the-desktop-listener-has-no-api-tier
  (is (= 404 (:status (POST "/api/commands/app/open" {:app "files"})))
      "/api is another machine's door — it is not on this socket at all"))
