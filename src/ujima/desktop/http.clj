(ns ujima.desktop.http
  "The eww-facing local API (http-kit, loopback). Two directions:
     - state   : GET /api/desktop/stream pushes the projection snapshot as NDJSON (one JSON line
                 per change) for eww `deflisten`; GET /api/desktop/windows returns it once.
     - command : POST open/focus/close. Commands are event-sourced — they fire i3/launch actions
                 and let the resulting i3 window events flow back through the single writer (the
                 i3 event thread). They never mutate the projection here.
   ctx = {:state* :lifecycle* :subs* :catalog :launch-ctx}."
  (:require [clojure.string     :as str]
            [org.httpkit.server :as http]
            [lib.edn            :refer [edn->json]]
            [lib.shell          :as shell]
            [ujima.log          :as log]
            [ujima.desktop.catalog :as catalog]
            [ujima.desktop.launch  :as launch]
            [ujima.desktop.windows :as windows]
            [ujima.desktop.lifecycle :as lc]
            [ujima.desktop.i3      :as i3]))


(defn- json [status body]
  {:status status :headers {"content-type" "application/json"} :body (edn->json body)})

(defn- ok  [body] (json 200 body))
(defn- bad [msg]  (json 400 {:error msg}))
(defn- nf  []     (json 404 {:error "not found"}))


(defn- snapshot-line [state*]
  (str (edn->json (windows/snapshot @state*)) "\n"))


(defn broadcast!
  "Push the current snapshot (one NDJSON line) to every stream subscriber."
  [{:keys [state* subs*]}]
  (let [line (snapshot-line state*)]
    (doseq [ch @subs*] (http/send! ch line false))))


(defn- stream [{:keys [state* subs*]} req]
  (http/as-channel req
    {:on-open  (fn [ch] (swap! subs* conj ch) (http/send! ch (snapshot-line state*) false))
     :on-close (fn [ch _] (swap! subs* disj ch))}))


(defn route
  "Pure: (method, uri) -> [action & params], or nil. (close-current is matched before the generic
   window close.)"
  [method uri]
  (let [parts (->> (-> uri
                       (str/replace #"^/api/desktop/?" "")
                       (str/split #"/"))
                   (remove str/blank?)
                   vec)]
    (cond
      (and (= method :get)  (= parts ["stream"]))                    [:stream]
      (and (= method :get)  (= parts ["windows"]))                   [:snapshot]
      (and (= method :post) (= parts ["windows" "current" "close"])) [:close-current]
      (and (= method :post) (= (count parts) 3)
           (= (first parts) "apps")    (= (last parts) "open"))      [:open  (second parts)]
      (and (= method :post) (= (count parts) 3)
           (= (first parts) "windows") (= (last parts) "focus"))     [:focus (second parts)]
      (and (= method :post) (= (count parts) 3)
           (= (first parts) "windows") (= (last parts) "close"))     [:close (second parts)]
      :else nil)))


;; --- command handlers (event-sourced: fire i3/launch, never mutate the projection) ---

(defn- open-app! [{:keys [state* lifecycle* catalog launch-ctx]} id]
  (let [app (catalog/app catalog (keyword id))]
    (cond
      (nil? app)             (bad "unknown app")
      (= :shell (:kind app)) (do (i3/command! "workspace" "launcher") (ok {:focused "launcher"}))
      :else
      (if-let [wid (windows/window-for-app @state* (:id app))]
        (do (i3/command! "workspace" wid) (ok {:focused wid}))               ; :single -> focus existing
        (do (swap! lifecycle* lc/open (:id app) (System/currentTimeMillis))  ; await its window (gates sync!)
            (apply shell/sh (launch/launch-argv app launch-ctx))             ; -> window::new flows back
            (ok {:launched (:id app)}))))))

(defn- focus-window! [_ wid] (i3/command! "workspace" wid) (ok {:focused wid}))

(defn- close-window! [{:keys [state* lifecycle*]} wid]
  (if-let [w (windows/window @state* wid)]
    (do (swap! lifecycle* lc/closing (:app-id w))                            ; mark :closing until confirmed
        (doseq [con (windows/con-ids @state* wid)] (i3/close-con! con))      ; graceful: WM_DELETE
        (ok {:closing wid}))
    (nf)))

(defn- close-current! [{:keys [state*] :as ctx}]
  (let [cur (windows/current @state*)]
    (if (string? cur) (close-window! ctx cur) (ok {:noop cur}))))


(defn- handler [ctx req]
  (try
    (let [[action param] (route (:request-method req) (:uri req))]
      (case action
        :stream        (stream ctx req)
        :snapshot      (ok (windows/snapshot @(:state* ctx)))
        :open          (open-app!     ctx param)
        :focus         (focus-window! ctx param)
        :close         (close-window! ctx param)
        :close-current (close-current! ctx)
        (nf)))
    (catch Throwable e
      (log/error "desktop http handler failed" {:error (ex-message e)})
      (bad "internal error"))))


(defn start!
  "Start the loopback API. Returns the http-kit stop fn."
  [ctx {:keys [host port] :or {host "127.0.0.1" port 1337}}]
  (log/info "desktop http listening" {:host host :port port})
  (http/run-server (partial handler ctx) {:ip host :port port}))
