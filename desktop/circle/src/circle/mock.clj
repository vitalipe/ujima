(ns circle.mock
  "File-backed mock fleet: tmp/circle/world.edn IS the world. Reads slurp it,
   verb effects write back into it, so drift, effects and hand-edits share one
   visible truth. :knobs drive per-peer reply behavior; this whole ns is
   replaced by the real transport at connect time."
  (:require [clojure.edn     :as edn]
            [clojure.java.io :as io]
            [clojure.pprint  :as pprint]))


(def ^:private default-knob   {:reply :ok :delay-ms 500})
(def ^:private lost-reply-ms  8000)   ;; > the fleet 5s deadline, so :noreply resolves there
(def ^:private reboot-ms      15000)

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

;; nil = the peer replied but could not do it -> :fail
(defn- apply-verb [world peer verb args]
  (case verb
    :mute      (assoc-in peer [:audio :muted] true)
    :unmute    (assoc-in peer [:audio :muted] false)
    :volume    (-> peer
                   (assoc-in [:audio :volume] (:value args))
                   (assoc-in [:audio :muted] false))
    :close-app (assoc-in peer [:apps :running] nil)
    :open-app  (open-app world peer args)
    :open-url  (assoc-in peer [:apps :running] {:id       :web
                                                :name     (url->host (:url args))
                                                :category :web})
    :lock      (assoc-in peer [:desktop :locked] true)
    :unlock    (assoc-in peer [:desktop :locked] false)
    :restart   (-> peer
                   (assoc :online false)
                   (assoc-in [:apps :running] nil)
                   (assoc-in [:desktop :locked] false))
    :poweroff  (-> peer
                   (assoc :online false)
                   (assoc-in [:apps :running] nil))))

(defn- effect! [path id verb args]
  (locking world-lock
    (let [world (read-world path)
          peer  (peer-of world id)]
      (when-let [peer (and peer (apply-verb world peer verb args))]
        (write-world! path (replace-peer world peer))
        :ok))))


(defn- send! [path id verb args]
  (let [world (read-world path)
        peer  (peer-of world id)
        knob  (merge default-knob (get-in world [:knobs id]))]
    (cond
      (or (nil? peer) (not (:online peer)) (= :noreply (:reply knob)))
      (do (Thread/sleep lost-reply-ms) :noreply)

      (= :fail (:reply knob))
      (do (Thread/sleep (:delay-ms knob)) :fail)

      :otherwise
      (do (Thread/sleep (:delay-ms knob))
          (let [reply (or (effect! path id verb args) :fail)]
            (when (and (= :ok reply) (= :restart verb))
              (future (Thread/sleep reboot-ms)
                      (update-peer! path id #(assoc % :online true))))
            reply)))))


(defn seed!
  "Copies the committed seed to the live world file unless one already exists.
   Delete the live file to reseed."
  [seed-path live-path]
  (let [live (io/file live-path)]
    (when-not (.exists live)
      (io/make-parents live)
      (spit live (slurp seed-path)))))

(defn transport
  "The two-function seam the fleet runs over (+ :self for the view). The real
   transport implements the same map with HTTP calls to peers."
  [path]
  {:self  (fn [] (:self (read-world path)))
   :peers (fn [] (let [{:keys [catalog peers]} (read-world path)]
                   (->> peers
                        (mapv (fn [p] (update-in p [:apps :catalog] #(or % catalog))))
                        (sort-by :id)
                        vec)))
   :send! (partial send! path)})
