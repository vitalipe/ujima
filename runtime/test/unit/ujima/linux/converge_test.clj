(ns ujima.linux.converge-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.linux.converge :as converge]
            [ujima.linux.audio    :as audio]
            [ujima.linux.keyboard :as keyboard]
            [ujima.linux.system   :as system]
            [ujima.linux.net.wifi :as wifi]))


;; Desired-vs-actual decision logic only (no OS): audio/full-topology and the setters are
;; stubbed; :set-style stubs record what they were asked to do. Fixtures stay scalar.


(defn- records  [settings] (update-vals settings #(hash-map :effective %)))
(defn- converge! [settings] (converge/converge! (records settings) nil))


(def ^:private world-hdmi-default
  {:default :hdmi
   :sinks   {:usb  {:id 60 :name "usb-a"  :volume 100 :muted false}
             :hdmi {:id 51 :name "hdmi-x" :volume 70  :muted false}}})

(def ^:private base-settings
  {[:audio :active]      :usb
   [:audio :usb :volume]  40
   [:audio :hdmi :volume] 70
   [:audio :muted]        false
   [:keyboard :layout]    "us"
   [:system :hostname]    "ujima"
   [:system :timezone]    "Africa/Dar_es_Salaam"
   [:network :wifi :mode]  :peer
   [:network :wifi :essid] "IOT"
   [:network :wifi :psk]   "1337hax0r"})

(def ^:private joined {:ssid "IOT" :psk "1337hax0r"})


(defn- quiet-others
  "Stub keyboard/system/wifi as already-in-sync so audio tests stay focused."
  [f]
  (with-redefs [keyboard/layout  (constantly "us")
                keyboard/layout! (fn [_] (throw (ex-info "unexpected" {})))
                system/hostname  (constantly "ujima")
                system/hostname! (fn [_] (throw (ex-info "unexpected" {})))
                system/timezone  (constantly "Africa/Dar_es_Salaam")
                system/timezone! (fn [_] (throw (ex-info "unexpected" {})))
                wifi/radio       (constantly true)
                wifi/radio!      (fn [_] (throw (ex-info "unexpected" {})))
                wifi/network     (constantly joined)
                wifi/join!       (fn [_] (throw (ex-info "unexpected" {})))]
    (f)))


(deftest switches-to-active-and-asserts-only-real-diffs
  (quiet-others
    #(let [switched (atom nil) applied (atom {})]
       (with-redefs [audio/full-topology          (constantly world-hdmi-default)
                     audio/switch-output! (fn [id] (reset! switched id))
                     audio/apply-sink!    (fn [id d] (swap! applied assoc id d))]
         (converge! base-settings))
       (is (= 60 @switched)               "active :usb present but hdmi is default -> switch")
       (is (= {60 {:volume 40}} @applied) "usb corrected to its class volume; hdmi already in sync"))))


(deftest no-switch-when-active-is-default-or-absent
  (quiet-others
    #(let [switched (atom [])]
       (with-redefs [audio/full-topology          (constantly (assoc world-hdmi-default :default :usb))
                     audio/switch-output! (fn [id] (swap! switched conj id))
                     audio/apply-sink!    (fn [_ _])]
         (converge! base-settings)
         (converge! (assoc base-settings [:audio :active] nil))
         (converge! (assoc base-settings [:audio :active] :hdmi)))
       (is (= [] (remove #{51} @switched)) "never switches to a sink that's absent or already right")
       (is (= [51] (filter #{51} @switched)) "the hdmi case did switch (usb was default)"))))


(deftest mute-is-machine-wide-across-all-present-sinks
  (quiet-others
    #(let [applied (atom {})]
       (with-redefs [audio/full-topology          (constantly (assoc world-hdmi-default :default :usb))
                     audio/switch-output! (fn [_])
                     audio/apply-sink!    (fn [id d] (swap! applied assoc id d))]
         (converge! (assoc base-settings [:audio :muted] true)))
       (is (= {60 {:volume 40 :muted true}
               51 {:muted true}}
              @applied)
           "every present sink gets the mute flag; volume only where it differs"))))


(deftest sick-audio-domain-does-not-block-the-others
  (let [settings       (assoc base-settings [:keyboard :layout] "tz")
        layout-applied (atom nil)]
    (with-redefs [audio/full-topology      (fn [] (throw (ex-info "pipewire down" {})))
                  keyboard/layout  (constantly "us")
                  keyboard/layout! (fn [v] (reset! layout-applied v))
                  system/hostname  (constantly "ujima")
                  system/hostname! (fn [_])
                  system/timezone  (constantly "Africa/Dar_es_Salaam")
                  system/timezone! (fn [_])
                  wifi/radio       (constantly true)
                  wifi/network     (constantly joined)]
      (is (= (records settings) (converge! settings)) "does not throw, returns settings"))
    (is (= "tz" @layout-applied) "keyboard converged despite audio throwing")))


(deftest keyboard-and-system-apply-only-on-difference
  (let [calls (atom [])]
    (with-redefs [audio/full-topology      (constantly {:default nil :sinks {}})
                  audio/apply-sink! (fn [_ _])
                  audio/switch-output! (fn [_])
                  keyboard/layout  (constantly "us")
                  keyboard/layout! (fn [v] (swap! calls conj [:layout v]))
                  system/hostname  (constantly "old-name")
                  system/hostname! (fn [v] (swap! calls conj [:hostname v]))
                  system/timezone  (constantly "Africa/Dar_es_Salaam")
                  system/timezone! (fn [v] (swap! calls conj [:timezone v]))
                  wifi/radio       (constantly true)
                  wifi/network     (constantly joined)
                  wifi/join!       (fn [v] (swap! calls conj [:join v]))]
      (converge! base-settings))
    (is (= [[:hostname "ujima"]] @calls)
        "only the drifted hostname converged; layout, timezone and wifi were in sync")))


(defn- wifi-calls
  "Run converge over SETTINGS against a wifi world of RADIO + NETWORK; -> the wifi calls made."
  [settings radio network]
  (let [calls (atom [])]
    (with-redefs [audio/full-topology  (constantly {:default nil :sinks {}})
                  keyboard/layout      (constantly "us")
                  system/hostname      (constantly "ujima")
                  system/timezone      (constantly "Africa/Dar_es_Salaam")
                  wifi/radio           (constantly radio)
                  wifi/radio!          (fn [on?] (swap! calls conj [:radio on?]))
                  wifi/network         (constantly network)
                  wifi/join!           (fn [v] (swap! calls conj [:join v]))]
      (converge! settings))
    @calls))


(deftest wifi-asserts-the-profile-only-on-difference
  (is (= [] (wifi-calls base-settings true joined))
      "radio on and the profile already holds the network: nothing to do")
  (is (= [[:join joined]] (wifi-calls base-settings true nil))
      "no profile yet: join")
  (is (= [[:join {:ssid "IOT" :psk "other"}]]
         (wifi-calls (assoc base-settings [:network :wifi :psk] "other") true joined))
      "a changed psk is a changed network")
  (is (= [[:join {:ssid "Open" :psk nil}]]
         (wifi-calls (assoc base-settings [:network :wifi :essid] "Open" [:network :wifi :psk] nil)
                     true joined))
      "an open network is a profile without a psk"))


(deftest wifi-radio-follows-mode-and-off-leaves-the-profile-alone
  (is (= [[:radio true] [:join joined]] (wifi-calls base-settings false nil))
      "radio down under :peer comes up, then the profile is asserted")
  (is (= [[:radio false]]
         (wifi-calls (assoc base-settings [:network :wifi :mode] :off) true nil))
      ":off turns the radio off and says nothing about the profile")
  (is (= [] (wifi-calls (assoc base-settings [:network :wifi :mode] :off) false nil))
      "already off: nothing to do"))


(deftest clock-lifts-only-a-lagging-clock
  (quiet-others
    (fn []
      (let [set-to (atom nil)]
        (with-redefs [audio/full-topology (constantly {:default nil :sinks {}})
                      system/clock!       (fn [ms] (reset! set-to ms))]
          (converge! (assoc base-settings [:system :clock :epoch-floor] 123))
          (is (nil? @set-to) "a floor in the past leaves the clock alone")
          (let [ahead (+ (System/currentTimeMillis) 60000)]
            (converge! (assoc base-settings [:system :clock :epoch-floor] ahead))
            (is (= ahead @set-to) "a lagging clock rises to the floor")))))))
