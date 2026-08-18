(ns ujima.events.token-test
  (:require [clojure.test :refer [deftest is testing]]
            [ujima.desktop.app   :as app]
            [ujima.linux.systemd :as systemd]
            [ujima.events.token :as token]))


(defn- mounted [& tokens]
  [{:uuid "U" :state :mounted :mount "/ujima/run/storage/U" :tokens (vec tokens)}])


(def ^:private secret {:type :circle/secret :value {:key "abc" :circle "room-1"}})


;; --- what counts as a token -------------------------------------------------

(deftest a-circle-marker-with-a-key-is-the-token
  (is (= "abc" (token/circle-token (mounted secret)))))


(deftest nothing-is-not-a-token
  (is (nil? (token/circle-token nil))       "no previous push at all")
  (is (nil? (token/circle-token [])))
  (is (nil? (token/circle-token (mounted))) "a stick with no markers")
  (is (nil? (token/circle-token (mounted {:type :circle/secret :value nil})))
      "present but unparseable — storage reports it, we do not act on it")
  (is (nil? (token/circle-token (mounted {:type :circle/secret :value {:circle "room-1"}})))
      "parsed but no :key")
  (is (nil? (token/circle-token (mounted {:type :circle/secret :value {:key "   "}})))
      "blank key"))


;; --- the edge ---------------------------------------------------------------

(deftest transitions
  (testing "arrival opens"
    (is (= :open (token/transition nil "abc"))))

  (testing "a first push has no before, so a stick already in at boot reads as arrival"
    (is (= :open (token/transition (token/circle-token nil) "abc"))))

  (testing "unchanged does nothing — level-triggering here would yank the workspace"
    (is (nil? (token/transition "abc" "abc"))))

  (testing "departure closes"
    (is (= :close (token/transition "abc" nil))))

  (testing "absent stays absent"
    (is (nil? (token/transition nil nil))))

  (testing "a swapped stick with a different key is an arrival, not a no-op"
    (is (= :open (token/transition "abc" "xyz")))))


;; --- effects ----------------------------------------------------------------

(deftest opening-hands-the-console-the-key-then-runs-it
  (let [calls* (atom [])]
    (with-redefs [systemd/active?    (constantly false)
                  app/reset-app-env! (fn [id env] (swap! calls* conj [:env id env]))
                  app/run!           (fn [id]     (swap! calls* conj [:run id]))]
      (is (= :open (token/on-storage! (mounted secret) nil)))
      (is (= [[:env :console {"UJIMA_CIRCLE_TOKEN" "abc"}]
              [:run :console]]
             @calls*)
          "env must be set BEFORE run! — the launch reads it"))))


(deftest a-quiet-storage-event-touches-nothing
  (let [calls* (atom [])]
    (with-redefs [systemd/active?    (constantly false)
                  app/reset-app-env! (fn [& _] (swap! calls* conj :env))
                  app/run!           (fn [& _] (swap! calls* conj :run))]
      (is (nil? (token/on-storage! (mounted secret) (mounted secret))))
      (is (= [] @calls*) "the token was already there — no workspace switch"))))


;; --- the eject grace --------------------------------------------------------

(deftest a-departure-that-stays-gone-closes-the-console
  (let [stopped* (atom [])]
    (with-redefs [token/eject-grace-ms 60
                  systemd/active?      (constantly false)
                  app/reset-app-env!   (fn [& _] nil)
                  app/run!             (fn [& _] nil)
                  systemd/stop!        (fn [id] (swap! stopped* conj id))]
      (is (= :close (token/on-storage! [] (mounted secret))))
      (Thread/sleep 200)
      (is (= [:console] @stopped*)))))


(deftest a-reinsert-inside-the-grace-cancels-the-close
  (let [stopped* (atom [])]
    (with-redefs [token/eject-grace-ms 60
                  systemd/active?      (constantly false)
                  app/reset-app-env!   (fn [& _] nil)
                  app/run!             (fn [& _] nil)
                  systemd/stop!        (fn [id] (swap! stopped* conj id))]
      (token/on-storage! [] (mounted secret))            ; yanked -> close armed
      (Thread/sleep 20)
      (token/on-storage! (mounted secret) [])            ; back before the grace ran out
      (Thread/sleep 200)
      (is (= [] @stopped*)
          "a yank is not always an eject — the armed close must become a no-op"))))


(deftest a-flapping-stick-does-not-yank-the-workspace
  (let [calls* (atom [])]
    (with-redefs [token/eject-grace-ms 5000                  ; the close never gets to fire
                  systemd/active?      (constantly true)     ; the console is still up
                  app/reset-app-env!   (fn [& _] nil)
                  app/run!             (fn [id] (swap! calls* conj id))]
      (token/on-storage! [] (mounted secret))                ; bad contact drops it
      (is (= :open (token/on-storage! (mounted secret) []))) ; and it comes straight back
      (is (= [] @calls*)
          "app/run! switches workspace before its own gate, so calling it here would steal
           focus from whoever is typing, once per flap"))))
