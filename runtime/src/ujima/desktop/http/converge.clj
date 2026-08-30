(ns ujima.desktop.http.converge
  "The GUI's converge ports and the streams they feed. control hands it the
   whole settings plane, so this projects; the app layer projects before its
   targets run, so that one republishes as-is."
  (:require [lib.http.ndjson        :as ndjson]
            [ujima.control.queries  :as queries]
            [ujima.linux.devicetree :as devicetree]))

(def pinned-app-order {:files   0   ; the one a user reaches for — it must not move
                       :console 1})

;; machine identity is static — one devicetree read (nil off-Pi: x86 dev hosts)
(def ^:private serial-tail (delay (devicetree/serial-tail)))

(defn- lockable [record]
  {:value  (:effective record)
   :locked (= :activity (:via record))})


(defn settings->ui
  "Settings records (+ the static serial tail) -> the UI blob."
  [settings serial]
  (let [output (:effective (get settings [:audio :active]))]

    {:system   {:name        (:effective (get settings [:system :name]))
                :serial-tail serial}

     :audio    {:volume (lockable (get settings [:audio output :volume]))
                :muted  (lockable (get settings [:audio :muted]))
                :output (lockable (get settings [:audio :active]))}

     :keyboard {:layout  (lockable (get settings [:keyboard :layout]))
                :layouts (queries/ordered-layouts settings)
                :next    (queries/next-keyboard-layout settings)}}))


(defn apps->ui
  "The app snapshot -> the shell's blob. This is the wire contract."
  [{:keys [running catalog current mode]}]
  {:mode    mode
   :running running
   :current current
   :pinned  (->> catalog
             (remove :hidden)
             (filter (fn [{category :category}] (= category :system)))
             (map #(select-keys % [:id :label :icon]))
             (sort-by #(pinned-app-order (:id %) 99)))})


(defn converge-ui!   [settings _prv] (ndjson/publish! :ui/state (settings->ui settings @serial-tail)))
(defn converge-apps! [snapshot _prv] (ndjson/publish! :ui/apps  (apps->ui snapshot)))

(defn stream-ui   [req] (ndjson/subscribe! :ui/state req))
(defn stream-apps [req] (ndjson/subscribe! :ui/apps  req))


(defn init!
  "Declare the topics before anything serves or converges."
  []
  (ndjson/topic! :ui/state)
  (ndjson/topic! :ui/apps {:running [] :pinned [] :current nil}))
