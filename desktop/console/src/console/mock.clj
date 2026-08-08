(ns console.mock
  "File-backed mock fleet: tmp/console/world.edn IS the world. Reads slurp it,
   verb effects write back into it, so drift, effects and hand-edits share one
   visible truth. :knobs drive per-peer reply behavior; this whole ns is
   replaced by the real transport at connect time.
   Setup additions: :settings/write and :clock/set mutate the peer's domain
   maps, :checks/run derives its results from the world (+ :checks knob
   overrides), and the panel-plane :removed set backs remove/rescan — the real
   transport implements those over the console's own remembered-peers store."
  (:require [clojure.edn     :as edn]
            [clojure.java.io :as io]
            [clojure.pprint  :as pprint]
            [clojure.string  :as str])
  (:import  [java.time Duration Instant LocalDateTime ZoneId]))


(def ^:private default-knob   {:reply :ok :delay-ms 500})
(def ^:private lost-reply-ms  8000)   ;; > the jobs 5s deadline, so :noreply resolves there
(def ^:private reboot-ms      15000)
(def ^:private rescan-ms      1100)

(defonce ^:private world-lock (Object.))


(defn- read-world [path]
  (edn/read-string (slurp path)))

(defn- write-world! [path world]
  (spit path (with-out-str (pprint/pprint world))))

(defn- peer-of [world id]
  (first (filter #(= id (:id %)) (:peers world))))

(defn- replace-peer [world peer]
  (update world :peers (fn [peers] (mapv #(if (= (:id peer) (:id %)) peer %) peers))))

(defn- update-peer! [path id f]
  (locking world-lock
    (let [world (read-world path)]
      (when-let [peer (peer-of world id)]
        (write-world! path (replace-peer world (f peer)))))))


(defn- url->host [url]
  (or (second (re-find #"^(?:[a-z+]+://)?([^/:?#]+)" (str url))) (str url)))

(defn- open-app [world peer args]
  (let [catalog (or (get-in peer [:apps :catalog]) (:catalog world))
        app     (first (filter #(= (keyword (:app args)) (:id %)) catalog))]
    (when app (assoc-in peer [:apps :running] app))))

;; the mock's projection of a settings-path write onto the peer wire shape
(defn- write-setting [peer path value]
  (case path
    [:system :hostname]            (assoc peer :name value)
    [:system :timezone]            (assoc-in peer [:system :timezone] value)
    [:keyboard :available-layouts] (assoc-in peer [:keyboard :layouts] value)
    [:audio :active]               (assoc-in peer [:audio :out] value)
    nil))

(defn- clock-offset-min
  "Minutes the submitted local date+time in tz sits from actual now."
  [{:keys [tz date time]}]
  (let [set-at (-> (LocalDateTime/parse (str date "T" time))
                   (.atZone (ZoneId/of tz))
                   (.toInstant))]
    (.toMinutes (Duration/between (Instant/now) set-at))))

;; nil = the peer replied but could not do it -> :fail
(defn- apply-verb [world peer verb args]
  (case verb
    :mute           (assoc-in peer [:audio :muted] true)
    :unmute         (assoc-in peer [:audio :muted] false)
    :volume         (-> peer
                        (assoc-in [:audio :volume] (:value args))
                        (assoc-in [:audio :muted] false))
    :close-app      (assoc-in peer [:apps :running] nil)
    :open-app       (open-app world peer args)
    :open-url       (assoc-in peer [:apps :running] {:id       :web
                                                     :name     (url->host (:url args))
                                                     :category :web})
    :lock           (assoc-in peer [:desktop :locked] true)
    :unlock         (assoc-in peer [:desktop :locked] false)
    :settings/write (reduce-kv (fn [p path value]
                                 (and p (write-setting p path value)))
                               peer (:writes args))
    :clock/set      (-> peer
                        (assoc-in [:system :timezone] (:tz args))
                        (assoc-in [:system :clock-off-min] (clock-offset-min args)))
    :restart        (-> peer
                        (assoc :online false)
                        (assoc-in [:apps :running] nil)
                        (assoc-in [:desktop :locked] false))
    :poweroff       (-> peer
                        (assoc :online false)
                        (assoc-in [:apps :running] nil))))

(defn- effect! [path id verb args]
  (locking world-lock
    (let [world (read-world path)
          peer  (peer-of world id)]
      (when-let [peer (and peer (apply-verb world peer verb args))]
        (write-world! path (replace-peer world peer))
        :ok))))


;; ── checks: derived from the world, :checks knob overrides on top ──────────
(defn- humanize-min [m]
  (let [m (abs m)]
    (if (< m 60) (str m " min") (str "~" (quot m 60) " h"))))

(defn- peers-check [world id]
  (let [others (->> (:peers world)
                    (remove #(= id (:id %)))
                    (remove #(contains? (set (:removed world)) (:id %)))
                    (filter :online))
        missed (filter #(= :noreply (get-in world [:knobs (:id %) :reply])) others)
        n      (count others)]
    (if (empty? missed)
      {:id :peers :status :ok :label (str n " of " n)}
      {:id     :peers :status :warn
       :label  (str (- n (count missed)) " of " n)
       :note   (str (str/join ", " (map :name missed))
                    " did not reply — if several machines miss each other,"
                    " check the access point for client isolation")})))

(defn- storage-check [peer]
  (let [free (get-in peer [:system :store-free])]
    (cond
      (nil? free) {:id :storage :status :warn :label "unknown"}
      (< free 10) {:id :storage :status :warn :label (str free "% free")
                   :note "storage is almost full"}
      :otherwise  {:id :storage :status :ok :label (str free "% free")})))

(defn- clock-check [peer]
  (let [off (get-in peer [:system :clock-off-min] 0)]
    (if (<= (abs off) 2)
      {:id :clock :status :ok :label "OK"}
      {:id     :clock :status :warn
       :label  (str "off by " (humanize-min off))
       :note   "set the clock from the Clock card"})))

(def ^:private override-label {:ok "OK" :warn "Check" :fail "Failed"})

(defn- run-checks [path id]
  (let [world     (read-world path)
        peer      (peer-of world id)
        overrides (get-in world [:knobs id :checks])
        derived   [{:id :gateway :status :ok :label "OK"}
                   {:id :internet :status :ok :label "OK"}
                   (peers-check world id)
                   (storage-check peer)
                   (clock-check peer)]]
    {:status :ok
     :data   {:checks (mapv (fn [{:keys [id] :as check}]
                              (if-let [status (get overrides id)]
                                {:id id :status status :label (override-label status "?")}
                                check))
                            derived)}}))


(defn- send! [path id verb args]
  (let [world (read-world path)
        peer  (peer-of world id)
        knob  (merge default-knob (get-in world [:knobs id]))]
    (cond
      (or (nil? peer) (not (:online peer)) (= :noreply (:reply knob)))
      (do (Thread/sleep lost-reply-ms) :noreply)

      (= :fail (:reply knob))
      (do (Thread/sleep (:delay-ms knob)) :fail)

      (= :checks/run verb)
      (do (Thread/sleep (:delay-ms knob))
          (run-checks path id))

      :otherwise
      (do (Thread/sleep (:delay-ms knob))
          (let [reply (or (effect! path id verb args) :fail)]
            (when (and (= :ok reply) (#{:restart} verb))
              (future (Thread/sleep reboot-ms)
                      (update-peer! path id #(assoc % :online true))))
            reply)))))


;; ── panel-plane ops: the remembered list, not the machines ─────────────────
(defn- remove! [path id]
  (locking world-lock
    (let [world (read-world path)]
      (write-world! path (update world :removed (fnil conj #{}) id)))))

(defn- rescan!
  "The fake sweep: removed machines were 'plugged back in' meanwhile — they
   return online with a fresh uptime. Deliberate mock theater (feel the UI)."
  [path]
  (Thread/sleep rescan-ms)
  (locking world-lock
    (let [world   (read-world path)
          revived (set (:removed world))]
      (write-world! path
                    (-> world
                        (assoc :removed #{})
                        (update :peers (partial mapv
                                                #(if (revived (:id %))
                                                   (-> % (assoc :online true) (assoc-in [:system :up-min] 1))
                                                   %)))))
      {:revived (count revived)})))


(defn seed!
  "Copies the committed seed to the live world file unless one already exists.
   Delete the live file to reseed."
  [seed-path live-path]
  (let [live (io/file live-path)]
    (when-not (.exists live)
      (io/make-parents live)
      (spit live (slurp seed-path)))))

(defn transport
  "The fleet seam the console runs over (+ :self for the views). :send! is the
   real-transport contract (HTTP to peers later); :remove!/:rescan! are the
   panel's remembered-list ops, console-owned even when transport is real."
  [path]
  {:self    (fn [] (:self (read-world path)))
   :peers   (fn [] (let [{:keys [catalog peers removed]} (read-world path)]
                     (->> peers
                          (remove #(contains? (set removed) (:id %)))
                          (mapv (fn [p] (update-in p [:apps :catalog] #(or % catalog))))
                          (sort-by :id)
                          vec)))
   :send!   (partial send! path)
   :remove! (partial remove! path)
   :rescan! (partial rescan! path)})
