(ns ujima.desktop.app-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.app :as app]))


(def ^:private raw
  {:apps [{:id :wikipedia :label "Wikipedia" :icon "wikipedia"
           :exec ["chromium" "--app=https://wikipedia.com"] :class-flag "--class"}
          {:id :write :label "Write" :icon "write"
           :exec ["libreoffice" "--writer"] :class "libreoffice-writer"}
          {:id :draw :label "Draw"
           :exec ["tuxpaint"] :class "TuxPaint.TuxPaint"}]})

(def ^:private cat (app/->catalog raw))


;; --- catalog -----------------------------------------------------------------

(deftest window-class-stamped-vs-natural
  (is (= "ujima-wikipedia"   (app/window-class {:id :wikipedia :class-flag "--class"})))
  (is (= "TuxPaint.TuxPaint" (app/window-class {:id :draw :class "TuxPaint.TuxPaint"}))))


(deftest catalog-indexes-lower-cased-classes
  ;; WM_CLASS casing varies by app — the adoption index is lower-cased
  (is (= :draw      (get-in cat [:class->id "tuxpaint.tuxpaint"])))
  (is (= :wikipedia (get-in cat [:class->id "ujima-wikipedia"]))))


(deftest listing-projects-in-order-with-icon-default
  (is (= [{:id :wikipedia :label "Wikipedia" :icon "wikipedia"}
          {:id :write     :label "Write"     :icon "write"}
          {:id :draw      :label "Draw"      :icon "draw"}]   ; :icon defaults to the id
         (app/listing cat))))


(deftest catalog-validates-loudly
  (is (thrown? clojure.lang.ExceptionInfo
        (app/->catalog {:apps [{:id :a :label "A"}]}))
      "missing :exec")
  (is (thrown? clojure.lang.ExceptionInfo
        (app/->catalog {:apps [{:id :a :label "A" :exec ["x"]}
                               {:id :a :label "A2" :exec ["y"] :class "y"}]}))
      "duplicate ids")
  (is (thrown? clojure.lang.ExceptionInfo
        (app/->catalog {:apps [{:id :a :label "A" :exec ["x"] :class "X" :class-flag "--class"}]}))
      "both class sources")
  (is (thrown? clojure.lang.ExceptionInfo
        (app/->catalog {:apps [{:id :a :label "A" :exec ["x"] :class "Same"}
                               {:id :b :label "B" :exec ["y"] :class "same"}]}))
      "shared WM_CLASS, case-insensitive"))


;; --- proc store reducer --------------------------------------------------------

(def ^:private s0 (app/init-procs cat))

(defn- fold [& evs] (reduce app/apply-event s0 evs))

(def ^:private wiki-new
  {:type :window/new :con-id 42 :class "ujima-wikipedia" :transient? false :title "Wikipedia"})


(deftest adopts-a-catalog-classed-window
  (let [s (fold wiki-new)]
    (is (= #{42} (get-in s [:procs :wikipedia :windows])))
    (is (= :running (get-in s [:procs :wikipedia :state])))
    (is (nil? (get-in s [:procs :wikipedia :pid])) "no spawn in this slice")
    (is (= [:wikipedia] (:order s)))
    (is (= :wikipedia (:current s)))))


(deftest ignores-unknown-classes-and-replays
  (is (= s0 (fold {:type :window/new :con-id 9 :class "firefox" :transient? false :title "x"})))
  (is (= (fold wiki-new) (fold wiki-new wiki-new)) "baseline replay is idempotent"))


(deftest extra-windows-attach-to-the-singleton
  (let [s (fold wiki-new
                {:type :window/new :con-id 43 :class "ujima-wikipedia" :transient? false :title "popup"})]
    (is (= #{42 43} (get-in s [:procs :wikipedia :windows])))
    (is (= [:wikipedia] (:order s)) "still one proc")))


(deftest transients-attach-but-never-create
  (let [dialog {:type :window/new :con-id 7 :class "libreoffice-writer" :transient? true
                :title "Tip of the Day"}]
    (is (= s0 (fold dialog)) "no host proc -> ignored")
    (let [s (fold {:type :window/new :con-id 6 :class "libreoffice-writer" :transient? false :title "doc"}
                  dialog)]
      (is (= #{6 7} (get-in s [:procs :write :windows]))))))


(deftest late-class-adopts-on-title
  ;; LibreOffice: maps with no class, the class rides a later title event
  (let [s (fold {:type :window/new   :con-id 5 :class nil :transient? false :title "Untitled"}
                {:type :window/title :con-id 5 :class "libreoffice-writer" :transient? false :title "Essay.odt"})]
    (is (= #{5} (get-in s [:procs :write :windows])))
    (is (= "Essay.odt" (get-in s [:procs :write :title])))))


(deftest title-updates-a-tracked-proc
  (let [s (fold wiki-new
                {:type :window/title :con-id 42 :class "ujima-wikipedia" :transient? false
                 :title "Wikipedia — Cats"})]
    (is (= "Wikipedia — Cats" (get-in s [:procs :wikipedia :title])))))


(deftest close-drops-the-proc-only-with-its-last-window
  (let [s2 (fold wiki-new
                 {:type :window/new :con-id 43 :class "ujima-wikipedia" :transient? false :title "p"})]
    (is (= #{42} (get-in (app/apply-event s2 {:type :window/close :con-id 43})
                         [:procs :wikipedia :windows]))
        "a dialog closing keeps the proc")
    (let [gone (reduce app/apply-event s2 [{:type :window/close :con-id 43}
                                           {:type :window/close :con-id 42}])]
      (is (nil? (get-in gone [:procs :wikipedia])))
      (is (= [] (:order gone)))
      (is (nil? (:current gone))))))


(deftest focus-follows-tracked-windows
  (is (= :wikipedia (:current (fold wiki-new {:type :window/focus :con-id 42}))))
  (is (nil? (:current (fold wiki-new {:type :window/focus :con-id 999})))
      "unmanaged focus clears the highlight"))


(deftest proc-exit-marks-the-state
  (is (= :exited (get-in (fold wiki-new {:type :proc/exit :app-id :wikipedia})
                         [:procs :wikipedia :state])))
  (is (= s0 (fold {:type :proc/exit :app-id :wikipedia})) "unknown proc ignored"))


(deftest snapshot-is-the-wire-shape
  (is (= {:apps [{:id :wikipedia :label "Wikipedia" :icon "wikipedia"
                  :state :running :title "Wikipedia"}]
          :current :wikipedia}
         (app/snapshot (fold wiki-new))))
  (is (= {:apps [] :current nil} (app/snapshot s0))))
