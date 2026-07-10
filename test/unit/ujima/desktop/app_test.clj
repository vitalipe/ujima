(ns ujima.desktop.app-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [lib.shell :as shell]
            [ujima.linux.i3 :as i3]
            [ujima.desktop.app :as app]))


;; The actor loop with i3 + spawning stubbed: reads come from world*, mutations land in fx*,
;; converged snapshots in pushed*. Verbs route straight into handle-event! (i3/emit! stub).


(def ^:private catalog-edn
  {:apps [{:id :paint :label "Paint" :exec ["tuxpaint" "--nolockfile"]}
          {:id :web   :label "Web"   :exec ["chromium"]}
          {:id :sky   :label "Sky"   :exec ["stellarium"] :mode :fullscreen}]})


(def ^:private world*  (atom nil))    ; {:wins [{:ws :focused? :title}] :focused-ws "..."}
(def ^:private fx*     (atom []))
(def ^:private pushed* (atom []))


(defn- win [ws & {:keys [focused? title floating? wtype con]}]
  {:ws ws :focused? (boolean focused?) :title (or title ws)
   :floating? (boolean floating?) :wtype (or wtype "normal") :con (or con 1)})

(defn- node [w]
  {:id (:con w) :window (+ 1000 (:con w)) :name (:title w)
   :focused (:focused? w) :window_type (:wtype w)})

(defn- tree [wins]
  {:type "root"
   :nodes (vec (for [[ws ws-wins] (group-by :ws wins)]
                 {:type "workspace" :name ws
                  :nodes          (mapv node (remove :floating? ws-wins))
                  :floating_nodes (mapv node (filter :floating? ws-wins))}))})

(defn- setup! [wins focused-ws]
  (reset! world*  {:wins wins :focused-ws focused-ws})
  (reset! fx*     [])
  (reset! pushed* [])
  (let [f (str (fs/create-temp-file {:prefix "apps" :suffix ".edn"}))]
    (fs/delete-on-exit f)
    (spit f (pr-str catalog-edn))
    (app/init! {:catalog          (app/load-catalog f)
                :converge-targets [(fn [next _] (swap! pushed* conj next))]})))

(defn- stubbed [f]
  (with-redefs [i3/get-tree!         (fn [] (tree (:wins @world*)))
                i3/focused-workspace (fn [] (:focused-ws @world*))
                i3/switch-workspace! (fn [ws] (swap! world* assoc :focused-ws ws)
                                              (swap! fx* conj [:switch ws]))
                i3/kill-focused!     (fn [] (swap! fx* conj [:kill]))
                i3/command?          (fn [crit & _] (swap! fx* conj [:unfloat crit]))
                i3/emit!             (fn [ev] (app/handle-event! ev))
                shell/sh             (fn [& args] (swap! fx* conj [:spawn (vec (rest args))])
                                                  {:proc nil})]
    (f)))

(defn- fx-of [k] (filterv #(= k (first %)) @fx*))
(defn- snap  [] (last @pushed*))
(defn- open-ids [] (mapv :id (:apps (snap))))
(defn- current-id [] (:id (:current (snap))))


;; --- run: switch-then-launch ---

(deftest run-switches-then-launches-a-closed-app
  (setup! [] "1")
  (stubbed #(app/run! :paint))
  (is (= [[:switch "paint"]] (fx-of :switch)))
  (is (= [[:spawn ["tuxpaint" "--nolockfile"]]] (fx-of :spawn)))
  (is (= :paint (current-id)) "the topbar shows the app you're opening")
  (is (= [] (open-ids)) "no window yet — not in the open list"))

(deftest run-focuses-a-running-app-without-relaunching
  (setup! [(win "paint" :focused? true)] "1")
  (stubbed #(app/run! :paint))
  (is (= [[:switch "paint"]] (fx-of :switch)))
  (is (= [] (fx-of :spawn)) "already running — switch only")
  (is (= [:paint] (open-ids))))

(deftest run-debounces-a-double-tap
  (setup! [] "1")
  (stubbed #(do (app/run! :paint) (app/run! :paint)))
  (is (= 1 (count (fx-of :spawn))) "the second tap while it's still opening never re-launches"))


;; --- switch / close ---

(deftest switch-goes-to-the-app-without-launching
  (setup! [(win "web")] "1")
  (stubbed #(app/switch-to! :web))
  (is (= [[:switch "web"]] (fx-of :switch)))
  (is (= [] (fx-of :spawn))))

(deftest close-kills-the-focused-window-on-an-app
  (setup! [(win "paint" :focused? true)] "paint")
  (stubbed #(app/close-focused!))
  (is (= [[:kill]] (fx-of :kill))))

(deftest close-is-gated-at-home
  (setup! [] "1")
  (stubbed #(app/close-focused!))
  (is (= [] (fx-of :kill)) "the launcher is never ours to close"))


;; --- go home when a workspace empties (guarded so a launching app isn't fled) ---

(deftest emptied-workspace-goes-home
  (setup! [(win "paint" :focused? true)] "paint")
  (stubbed
    #(do (swap! world* assoc :wins [])          ; the window closed
         (reset! fx* [])
         (app/handle-event! {:type :window/close})))
  (is (= [[:switch "1"]] (fx-of :switch)))
  (is (nil? (current-id)) "back home"))

(deftest launching-app-is-not-fled
  ;; just-launched app hasn't mapped its window yet — recently-ran? guards the empty workspace
  (setup! [] "1")
  (stubbed
    #(do (app/run! :paint)                       ; switch to paint, mark launched
         (swap! world* assoc :wins [])
         (reset! fx* [])
         (app/handle-event! {:type :window/close})))
  (is (= [] (fx-of :switch)) "held on the launching app's workspace"))


;; --- projection ---

(deftest a-plain-tick-only-projects
  (setup! [(win "web" :focused? true)] "web")
  (stubbed #(app/handle-event! {:type :window/change}))
  (is (= [] @fx*) "no world mutations")
  (is (= :web (current-id)))
  (is (= [:web] (open-ids))))

(deftest fullscreen-mode-is-declared-not-detected
  (setup! [(win "sky" :focused? true)] "sky")
  (stubbed #(app/handle-event! {:type :tick}))
  (is (true? (:fullscreen (:current (snap)))) "a :mode :fullscreen app hides the bars")
  (setup! [(win "web" :focused? true)] "web")
  (stubbed #(app/handle-event! {:type :tick}))
  (is (false? (:fullscreen (:current (snap))))))

(deftest floating-app-window-gets-tiled
  ;; chromium --app auto-floats + sets class/role late; the agent un-floats it
  (setup! [(win "web" :focused? true :floating? true :con 7)] "web")
  (stubbed #(app/handle-event! {:type :window/change}))
  (is (= [[:unfloat "[con_id=7]"]] (fx-of :unfloat))))


(deftest tiled-windows-and-dialogs-left-alone
  (setup! [(win "write" :focused? true :con 9)                              ; tiled
           (win "write" :floating? true :wtype "dialog" :con 10)] "write")  ; a real dialog
  (stubbed #(app/handle-event! {:type :window/change}))
  (is (= [] (fx-of :unfloat)) "a tiled window and a real dialog are untouched"))


(deftest snapshot-entry-shape
  (setup! [(win "web" :focused? true :title "Example — Chromium")] "web")
  (stubbed #(app/handle-event! {:type :tick}))
  (is (= {:id :web :label "Web" :icon "web" :category nil
          :title "Example — Chromium" :fullscreen false}
         (:current (snap)))))


;; --- open-url routes to the browser ---

(deftest open-url-launches-the-web-app-with-the-url
  (setup! [] "1")
  (stubbed #(app/open-url! "https://x.org"))
  (is (= [[:spawn ["chromium" "https://x.org"]]] (fx-of :spawn)))
  (is (= [[:switch "web"]] (fx-of :switch))))

(deftest open-url-rejects-non-http
  (setup! [] "1")
  (stubbed #(is (thrown? clojure.lang.ExceptionInfo (app/open-url! "ftp://nope")))))


;; --- verbs validate ---

(deftest verbs-resolve-in-the-catalog-or-throw
  (setup! [] "1")
  (stubbed
    #(do (is (thrown? clojure.lang.ExceptionInfo (app/run! :nope)))
         (is (thrown? clojure.lang.ExceptionInfo (app/switch-to! :nope)))))
  (is (= {:apps [] :current nil} (app/current-apps-state)) "held snapshot when nothing is open"))
