(ns ujima.desktop.app.proc-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.app.catalog :as catalog]
            [ujima.desktop.app.proc    :as proc]))


(def ^:private cat
  (catalog/->catalog
    {:apps [{:id :wikipedia :label "Wikipedia" :icon "wikipedia"
             :exec ["chromium" "--app=https://wikipedia.com"] :class-flag "--class"}
            {:id :write :label "Write" :icon "write"
             :exec ["libreoffice" "--writer"] :class "libreoffice-writer"}]}))

(def ^:private s0 (proc/init cat))

(defn- fold [& evs] (reduce proc/apply-event s0 evs))

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
    (is (= #{42} (get-in (proc/apply-event s2 {:type :window/close :con-id 43})
                         [:procs :wikipedia :windows]))
        "a dialog closing keeps the proc")
    (let [gone (reduce proc/apply-event s2 [{:type :window/close :con-id 43}
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
         (proc/snapshot (fold wiki-new))))
  (is (= {:apps [] :current nil} (proc/snapshot s0))))
