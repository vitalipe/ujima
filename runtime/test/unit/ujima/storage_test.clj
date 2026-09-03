(ns ujima.storage-test
  "The pure half: marker sweeping and state derivation. Mount effects are hardware, not
   unit tests."
  (:require [clojure.test  :refer [deftest is]]
            [babashka.fs   :as fs]
            [cheshire.core :as json]
            [lib.task      :as task]
            [lib.task.flow :refer [flow]]
            [ujima.storage :as storage]))


(def ^:private sweep      #'storage/sweep)
(def ^:private task->state #'storage/task->state)
(def ^:private ->entry    #'storage/->entry)


(defn- a-mount-with [filename content]
  (let [dir (str (fs/create-temp-dir))]
    (when filename
      (fs/create-dirs (fs/path dir "ujima"))
      (spit (str (fs/path dir "ujima" filename)) content))
    dir))


;; --- sweep ------------------------------------------------------------------

(deftest a-marker-is-reported-with-its-parsed-value
  (is (= {:circle/secret {:key "abc" :circle "room-1"}}
         (sweep (a-mount-with "circle.json"
                              (json/generate-string {:key "abc" :circle "room-1"}))))))


(deftest a-pack-registration-is-a-token-like-any-other
  (is (= {:ujima/pack {:pack "cool-game.pack" :label "Cool Game"}}
         (sweep (a-mount-with "install.json"
                              (json/generate-string {:pack  "cool-game.pack"
                                                     :label "Cool Game"}))))
      "the token is the data — whether the pack path exists is the install verb's question"))


(deftest a-stick-without-the-ujima-dir-reports-nothing
  (is (= {} (sweep (a-mount-with nil nil)))
      "no ujima/ dir — the one stat that gates everything")
  (let [dir (str (fs/create-temp-dir))]
    (spit (str (fs/path dir "circle.json")) "{}")
    (is (= {} (sweep dir))
        "marker names at the mount ROOT are not markers — the clean cut from v0.4")))


(deftest an-unreadable-marker-still-reports-itself
  (is (= {:circle/secret nil}
         (sweep (a-mount-with "circle.json" "{not json")))
      "presence is the finding — 'there is a token here and it is junk' beats silence"))


(deftest an-absurd-marker-is-not-slurped
  (let [dir (a-mount-with "circle.json" (apply str (repeat 70000 "x")))]
    (is (= {:circle/secret nil} (sweep dir))
        "over the cap the value is dropped — a 4GB file must never reach the daemon's heap")))


(deftest a-directory-named-like-a-marker-is-not-a-marker
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir "ujima" "circle.json"))
    (is (= {} (sweep dir)))))


;; --- state derivation -------------------------------------------------------

(deftest state-comes-from-presence-plus-the-task
  (is (= :detected (task->state nil))
      "present with no task is the only state that has an action")

  (let [cold (flow :t 1)]
    (is (= :mounting (task->state cold))
        "a task exists = in flight. run!! claims on the spawned thread, so a second event
         landing in that window must NOT see the one state that starts a mount"))

  (let [done (doto (flow :t {:mount "/x" :tokens []}) (task/run!!))]
    (is (= :mounted (task->state done))))

  (let [boom (doto (flow :t (throw (ex-info "mount: no such device" {}))) (task/run!!))]
    (is (= :invalid (task->state boom)))))


(deftest a-mounted-entry-carries-the-task-result
  (let [facts {:uuid "U" :disk "sda" :fstype "vfat"}
        t     (doto (flow :t {:mount "/ujima/run/storage/U" :tokens {:circle/secret {:key "abc"}}})
                (task/run!!))]
    (is (= {:uuid "U" :disk "sda" :fstype "vfat" :state :mounted
            :mount "/ujima/run/storage/U" :tokens {:circle/secret {:key "abc"}}}
           (->entry {:facts facts :task t})))))


(deftest an-invalid-entry-carries-why
  (let [t (doto (flow :t (throw (ex-info "mount: unknown filesystem type 'exfat'" {})))
            (task/run!!))
        e (->entry {:facts {:uuid "U"} :task t})]
    (is (= :invalid (:state e)))
    (is (= "mount: unknown filesystem type 'exfat'" (:reason e))
        "the reason comes off the task timeline, not a hand-maintained field")))


