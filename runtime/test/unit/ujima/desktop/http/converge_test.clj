(ns ujima.desktop.http.converge-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [lib.edn :refer [edn->json]]
            [ujima.desktop.app :as app]
            [ujima.desktop.http.converge :as converge]))


(defn- ui [settings]
  (converge/settings->ui (update-vals settings #(hash-map :effective %)) "af3c"))


(def ^:private settings
  {[:system :name]                 "meru-01"
   [:audio :active]                :usb
   [:audio :usb :volume]           55
   [:audio :hdmi :volume]          70
   [:audio :muted]                 false
   [:keyboard :layout]             "us"
   [:keyboard :available-layouts]  ["us" "tz"]})


(deftest settings->ui-projects-the-active-output
  (is (= {:system   {:name "meru-01" :serial-tail "af3c"}
          :audio    {:volume {:value 55 :locked false}
                     :muted  {:value false :locked false}
                     :output {:value :usb :locked false}}
          :keyboard {:layout {:value "us" :locked false} :layouts ["tz" "us"] :next "tz"}}
         (ui settings)))
  (is (= 70 (get-in (ui (assoc settings [:audio :active] :hdmi))
                    [:audio :volume :value]))
      "volume follows the active class"))


(deftest settings->ui-greys-out-without-an-active-output
  (is (= {:volume {:value nil :locked false}
          :muted  {:value false :locked false}
          :output {:value nil :locked false}}
         (:audio (ui (assoc settings [:audio :active] nil))))))


(deftest settings->ui-locks-what-an-activity-holds
  (let [held  (fn [m k] (assoc m k (assoc (get m k) :via :activity)))
        recs  (update-vals settings #(hash-map :effective % :via :session))
        ui*   #(converge/settings->ui % "af3c")]
    (is (false? (get-in (ui* recs) [:audio :volume :locked]))
        "a session value is the user's own")
    (is (true?  (get-in (ui* (held recs [:audio :usb :volume])) [:audio :volume :locked])))
    (is (false? (get-in (ui* (-> recs
                               (held [:audio :usb :volume])
                               (assoc [:audio :active] {:effective :hdmi :via :session})))
                        [:audio :volume :locked]))
        "the padlock follows the ACTIVE output — pinning usb does not pin hdmi")
    (is (true?  (get-in (ui* (held recs [:audio :muted]))     [:audio :muted :locked])))
    (is (true?  (get-in (ui* (held recs [:keyboard :layout])) [:keyboard :layout :locked])))))


(deftest settings->ui-carries-the-switcher-cycle
  (is (= "us" (get-in (ui (assoc settings [:keyboard :layout] "tz")) [:keyboard :next]))
      "the order itself is queries/next-keyboard-layout's contract — this is the wiring"))


;; --- apps->ui: which apps exist is the app layer's, where the shell shows them is ours ---

(def ^:private catalog
  [{:id :console :label "Console" :icon "console.svg" :category :system :hidden true}
   {:id :files   :label "Files"   :icon "files.svg"   :category :system :hidden false}
   {:id :paint   :label "Paint"   :icon "paint.svg"   :category :create :hidden false}])


(deftest pinned-is-the-system-apps-that-are-not-hidden
  (is (= [{:id :files :label "Files" :icon "files.svg"}]
         (:pinned (converge/apps->ui {:running [] :catalog catalog :current nil})))
      "files pins though nothing runs; console ships hidden; an ordinary app never pins"))


(deftest unhiding-puts-an-app-in-the-dock
  (let [shown (mapv #(cond-> % (= :console (:id %)) (assoc :hidden false)) catalog)]
    (is (= [:files :console]
           (mapv :id (:pinned (converge/apps->ui {:running [] :catalog shown :current nil}))))
        "declared order, NOT catalog order: a token arriving must not shift the Files icon")))


(deftest an-unlisted-pinned-app-goes-last
  ;; nil sorts BEFORE numbers, so an unknown id must not fall to the front of the dock
  (let [shown (conj (mapv #(cond-> % (= :console (:id %)) (assoc :hidden false)) catalog)
                    {:id :zzz :label "Z" :icon "z.svg" :category :system :hidden false})]
    (is (= [:files :console :zzz]
           (mapv :id (:pinned (converge/apps->ui {:running [] :catalog shown :current nil})))))))


(deftest the-wire-shape-is-exactly-four-keys
  (let [out (converge/apps->ui {:mode :multi :running [{:id :paint}] :catalog catalog :current {:id :paint}})]
    (is (= #{:mode :running :pinned :current} (set (keys out)))
        "spelled out, so an internal projection key can never leak to the shell")
    (is (= :multi (:mode out)))
    (is (= [{:id :paint}] (:running out)))
    (is (= {:id :paint}   (:current out)))))


(deftest a-token-on-a-catalog-entry-never-reaches-the-wire
  ;; :env holds UJIMA_CIRCLE_TOKEN — the projection carries the LISTING for this reason
  (let [leaky (mapv #(assoc % :env {"UJIMA_CIRCLE_TOKEN" "s3cret"} :exec ["x"]) catalog)
        out   (converge/apps->ui {:running [] :catalog leaky :current nil})]
    (is (not (re-find #"s3cret" (pr-str out)))
        "pinned select-keys the entry — assert over the WHOLE blob, not one field")))


;; --- places -----------------------------------------------------------------

(def ^:private mounted-entry
  {:uuid "6962-5E15" :disk "sda" :label nil :fstype "vfat" :rm true
   :state :mounted :mount "/ujima/run/storage/6962-5E15"
   :tokens {:circle/secret {:key "abc"}}})

(defn- usb-place [entry]
  (second (:places (converge/places->ui [entry] "/f"))))


(deftest places-lead-with-the-local-files-area
  (is (= {:places [{:id [:local "files"] :kind :local :state :ready :mount "/f"}]}
         (converge/places->ui [] "/f"))))


(deftest a-mounted-partition-is-a-ready-usb-place
  (is (= {:id     [:usb "6962-5E15"] :kind :usb :state :ready
          :mount  "/ujima/run/storage/6962-5E15"
          :label  nil :fstype "vfat"
          :tokens ["circle/secret"]}
         (usb-place mounted-entry))
      "plane plumbing (:rm :disk) stays behind; tokens flatten to type names —
       full ns/name strings, since the wire encoder drops keyword namespaces"))


(deftest token-values-never-reach-the-wire
  (let [rendered (edn->json (converge/places->ui [mounted-entry] "/f"))]
    (is (str/includes? rendered "circle/secret") "the type is the finding")
    (is (not (str/includes? rendered "abc"))
        "no value survives serialization — asserted over the WHOLE rendered output,
         so a later entry carrying a value cannot slip through a per-field check")))


(deftest an-invalid-partition-carries-why-and-no-mount
  (is (= {:id [:usb "X"] :kind :usb :state :invalid :label "KEYS" :fstype "vfat"
          :reason "mount: fail"}
         (usb-place {:uuid "X" :state :invalid :label "KEYS"
                     :fstype "vfat" :reason "mount: fail"}))))


(deftest in-flight-states-read-as-mounting
  (is (= :mounting (:state (usb-place {:uuid "X" :state :detected})))
      "detected is in flight by the time anyone sees it")
  (is (= :mounting (:state (usb-place {:uuid "X" :state :mounting})))))
