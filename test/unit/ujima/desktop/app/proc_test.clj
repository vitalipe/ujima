(ns ujima.desktop.app.proc-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.app.catalog :as catalog]
            [ujima.desktop.app.proc    :as proc]))


(def ^:private cat
  (catalog/->catalog
    {:apps [{:id :wikipedia :label "Wikipedia" :icon "wikipedia"
             :exec ["chromium" "--app=https://wikipedia.com" "--class=ujima-wikipedia"]
             :class "ujima-wikipedia"}
            {:id :write :label "Write" :icon "write"
             :exec ["libreoffice" "--writer"] :class "libreoffice-writer"}]}))

(def ^:private wiki-app (get-in cat [:by-id :wikipedia]))

(def ^:private s0 (proc/init (:class->app cat)))

(defn- fold [& evs] (reduce proc/apply-event s0 evs))

(def ^:private wiki-new
  {:type :window/new :con-id 42 :class "ujima-wikipedia" :transient? false :title "Wikipedia"})


(deftest adopts-a-catalog-classed-window
  (let [s (fold wiki-new)]
    (is (= #{42} (get-in s [:procs :wikipedia :windows])))
    (is (= :running (get-in s [:procs :wikipedia :state])))
    (is (= wiki-app (get-in s [:procs :wikipedia :app])) "the proc carries its app map")
    (is (nil? (get-in s [:procs :wikipedia :pid])) "recognized but not spawned by us")
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


(deftest started-enters-at-starting
  ;; New: in the dock from click time, no windows yet
  (let [s (fold {:type :proc/started :app wiki-app :pid 4242})]
    (is (= {:app-id :wikipedia :app wiki-app :pid 4242 :windows #{} :state :starting :title nil}
           (get-in s [:procs :wikipedia])))
    (is (= [:wikipedia] (:order s)))
    (is (nil? (:current s)) "no window, no focus"))
  (let [replay (fold {:type :proc/started :app wiki-app :pid 1}
                     {:type :proc/started :app wiki-app :pid 2})]
    (is (= 2 (get-in replay [:procs :wikipedia :pid])) "replay refreshes the pid")
    (is (= [:wikipedia] (:order replay)) "and never duplicates the proc")))


(deftest adoption-promotes-starting-to-running
  ;; New -> Running: the spawned proc's window maps and attaches
  (let [s (fold {:type :proc/started :app wiki-app :pid 4242}
                wiki-new)]
    (is (= :running (get-in s [:procs :wikipedia :state])))
    (is (= 4242 (get-in s [:procs :wikipedia :pid])) "pid survives the promotion")
    (is (= #{42} (get-in s [:procs :wikipedia :windows])))
    (is (= "Wikipedia" (get-in s [:procs :wikipedia :title])) "first window's title lands")))


(deftest started-learns-ad-hoc-classes
  ;; an app the catalog never heard of: :proc/started teaches the index its :class,
  ;; so its window adopts like any catalog app's
  (let [notes {:id :notes :label "Notes" :exec ["notes-app"] :class "Ujima-Notes"}
        s     (fold {:type :proc/started :app notes :pid 7}
                    {:type :window/new :con-id 9 :class "ujima-notes" :transient? false :title "Notes"})]
    (is (= :running (get-in s [:procs :notes :state])))
    (is (= #{9} (get-in s [:procs :notes :windows])))
    (is (= [{:id :notes :label "Notes" :icon "notes" :state :running :title "Notes"}]
           (:apps (proc/snapshot s)))
        "snapshot renders from the proc's own :app — no catalog anywhere")))


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
