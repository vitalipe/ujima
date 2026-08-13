(ns ujima.api.routes-test
  "Both route builders, driven through the real router."
  (:require [clojure.test :refer [deftest is]]
            [clj-simple-router.core :as router]
            [ujima.api.routes :as routes]))


(defn- built [seen]
  (routes/commands
    {:base "api/commands"
     :commands
     {"desktop/:scope/audio/volume"
      {:params  [:map
                 [:scope [:enum :session :activity]]
                 [:value [:or :int :double]]]
       :reply   [:map [:volume :int]]
       :handler (fn [params] (reset! seen params) {:volume (:value params)})}

      "desktop/close-app"
      {:handler (fn [params] (reset! seen params) :closed)}}}))


(defn- call [rs {:keys [uri query body]}]
  ((router/router rs)
   (cond-> {:request-method :post :uri uri}
     query (assoc :query query)
     body  (assoc :body body))))


(deftest slugs-become-the-routers-star
  (is (= #{"POST /api/commands/desktop/*/audio/volume"
           "POST /api/commands/desktop/close-app"}
         (set (keys (built (atom nil)))))))


(deftest params-arrive-from-slug-query-and-body
  (let [seen (atom nil)
        rs   (built seen)]
    (call rs {:uri "/api/commands/desktop/session/audio/volume" :body {:value 55}})
    (is (= {:scope :session :value 55} @seen) "the slug names itself, the shape decodes it")

    (call rs {:uri "/api/commands/desktop/session/audio/volume" :query {:value "55"}})
    (is (= {:scope :session :value 55} @seen) "a query string reads the same")

    (call rs {:uri  "/api/commands/desktop/session/audio/volume"
              :body {:scope :activity :value 1}})
    (is (= :session (:scope @seen)) "the slug wins — a body key can't rewrite the URL")))


(deftest a-slug-the-shape-rejects-is-not-our-route
  (is (nil? (call (built (atom nil))
                  {:uri "/api/commands/desktop/nope/audio/volume" :body {:value 1}}))
      "nil falls through to the edge's 404"))


(deftest anything-else-is-a-humanized-400
  (let [e (try (call (built (atom nil))
                     {:uri "/api/commands/desktop/session/audio/volume" :body {:value "x"}})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= "value should be an integer" (ex-message e)))
    (is (= :request/malformed (:error (ex-data e))) "the edge maps it to 400")))


(deftest reply-decides-the-status
  (let [rs (built (atom nil))]
    (is (= {:status 200 :body {:volume 55}}
           (call rs {:uri "/api/commands/desktop/session/audio/volume" :body {:value 55}})))
    (is (= {:status 202 :body {}}
           (call rs {:uri "/api/commands/desktop/close-app"}))
        "no :reply — accepted, no body")))


(deftest a-command-without-a-handler-dies-at-build
  (is (thrown? AssertionError
        (routes/commands {:base "api/commands" :commands {"x" {:params [:map]}}}))))


;; ── queries ─────────────────────────────────────────────────────────────────

(defn- tree [ran]
  (routes/queries
    {:base  "api/query/machine"
     :nodes {[:audio]            (fn [] (swap! ran conj :audio)
                                        {:volume 40 :muted false :output :usb})
             [:device]           (fn [] (swap! ran conj :device)
                                        {:serial nil :model "Pi 500"})
             [:apps]             (fn [] (swap! ran conj :apps) [{:id :gimp}])
             [:system :hostname] (fn [] (swap! ran conj :hostname) "ujima")
             [:system :clock-ms] (fn [] (swap! ran conj :clock-ms) 1786500000000)}}))


(defn- ask [rs uri]
  ((router/router rs) {:request-method :get :uri uri}))


(deftest a-node-answers-and-you-can-address-into-it
  (let [rs (tree (atom []))]
    (is (= {:status 200 :body {:volume 40 :muted false :output :usb}}
           (ask rs "/api/query/machine/audio")) "a map answers bare")
    (is (= {:status 200 :body [{:id :gimp}]}
           (ask rs "/api/query/machine/apps")) "so does a vector")
    (is (= {:status 200 :body {:volume 40}}
           (ask rs "/api/query/machine/audio/volume")) "a scalar wears the key asked for")
    (is (= {:status 200 :body {:serial nil}}
           (ask rs "/api/query/machine/device/serial"))
        "a nil the node carries is an answer, not a miss")))


(deftest paths-above-nodes-assemble
  (let [rs (tree (atom []))]
    (is (= {:status 200 :body {:hostname "ujima" :clock-ms 1786500000000}}
           (ask rs "/api/query/machine/system")) "grafted back from the nodes below")
    (is (= #{:audio :device :apps :system}
           (set (keys (:body (ask rs "/api/query/machine"))))) "the root assembles everything")))


(deftest a-path-no-node-carries-is-a-404
  (let [rs (tree (atom []))]
    (is (= 404 (:status (ask rs "/api/query/machine/audio/volme"))) "a typo below a node")
    (is (= 404 (:status (ask rs "/api/query/machine/nope"))))
    (is (= 404 (:status (ask rs "/api/query/machine/system/nope"))))
    (is (= 404 (:status (ask rs "/api/query/machine/apps/0"))) "you can't index into a leaf")))


(deftest only-the-nodes-asked-for-run
  (let [ran (atom [])
        rs  (tree ran)]
    (ask rs "/api/query/machine/audio")
    (is (= [:audio] @ran) "a narrow read touches one node")
    (reset! ran [])
    (ask rs "/api/query/machine/system")
    (is (= #{:hostname :clock-ms} (set @ran)) "an assembly touches only what's below it")))


(deftest the-deepest-node-wins
  (let [rs (routes/queries
             {:base  "api/query/machine"
              :nodes {[:desktop]           (constantly {:running {:id :coarse}})
                      [:desktop :running]  (constantly {:id :cheap})}})]
    (is (= {:status 200 :body {:id :cheap}} (ask rs "/api/query/machine/desktop/running"))
        "overlap is legal — the deeper node serves its own subtree")
    (is (= {:status 200 :body {:running {:id :coarse}}} (ask rs "/api/query/machine/desktop"))
        "and the coarse one still answers above it")))
