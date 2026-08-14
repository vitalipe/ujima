(ns ujima.api
  "The /api tier: query/machine a node per source, query/settings one node over control's
   records, commands the verbs below. A verb keeps :doc, :params and :handler together; the
   query side's reply shapes are pure data, in schema.ujima.api.query."
  (:require [ujima.api.routes       :as routes]
            [ujima.control          :as control]
            [ujima.control.queries  :as queries]
            [ujima.control.commands :as effects]
            [ujima.desktop.app      :as desktop]
            [ujima.linux.system     :as system]))


;; ex-info {:error kw} -> status (:request/malformed is lib.http's)
(def errors
  {:audio/no-output         409
   :keyboard/unknown-layout 409
   :settings/unknown        404
   :app/unknown-app         404
   :app/bad-url             400})


;; :device is config, not a moment
(def ^:private runtime-scope [:enum :session :activity])
(def ^:private scope         [:enum :device :session :activity])


;; ── the verbs ───────────────────────────────────────────────────────────────

(def commands
  {"app/open"     {:doc     "Open an app by catalog id."
                   :params  [:map [:app [:string {:min 1}]]]
                   :handler (fn [{:keys [app]}] (desktop/run! (keyword app)))}

   "app/switch"   {:doc     "Focus an app that is already open."
                   :params  [:map [:app [:string {:min 1}]]]
                   :handler (fn [{:keys [app]}] (desktop/switch-to! (keyword app)))}

   "app/close"    {:doc     "Close the focused app."
                   :handler (fn [_] (desktop/close-focused!))}

   "app/home"     {:doc     "Go to the home workspace."
                   :handler (fn [_] (desktop/go-home!))}

   "app/open-url" {:doc     "Open a URL in the Web app."
                   :params  [:map [:url [:string {:min 1}]]]
                   :handler (fn [{:keys [url]}] (desktop/open-url! url))}

   ;; not settings/**: the output is resolved at write time
   "audio/volume" {:doc     "Set the ACTIVE output's volume; the effect clamps to 0-100."
                   :params  [:map [:scope runtime-scope] [:value [:or :int :double]]]
                   :handler (fn [{:keys [scope value]}] (effects/change-current-volume! value scope))}

   ;; not settings/**: narrows against another setting's value
   "keyboard/layout" {:doc     "Set the layout; only codes in available-layouts are accepted."
                      :params  [:map [:scope runtime-scope] [:layout [:string {:min 1}]]]
                      :handler (fn [{:keys [scope layout]}] (effects/change-keyboard-layout! layout scope))}

   "settings/**"  {:doc     "Set any setting by its path; the def decides the legal scopes and values."
                   :params  [:map [:path [:vector :keyword]] [:scope scope] [:value :any]]
                   :handler (fn [{:keys [path value scope]}] (effects/change-setting! path value scope))}

   "system/restart"  {:doc     "Reboot this machine."
                      :handler (fn [_] (system/reboot!))}

   "system/poweroff" {:doc     "Power this machine off."
                      :handler (fn [_] (system/shutdown!))}})


;; ── the routes ──────────────────────────────────────────────────────────────

(defn endpoints [{:keys [version id]}]
  
  {:errors errors
   :routes
   (merge
     (routes/commands
      {:base     "commands"
       :commands commands})

     (routes/queries
      {:base  "query/machine"
       :nodes {"schema"   (constantly 1)
               "id"       (constantly id)
               "device"   (constantly {:serial "10000000deadbeef"
                                       :model  "Raspberry Pi 500 Rev 1.0"})
               "image"    (constantly {:version version})
               "disk"     (constantly {:type     :ab
                                       :slot     :a
                                       :storage  {:total-mb 28000 :free-mb 21500}
                                       :settings {:total-mb 256   :free-mb 249}})
               
               "apps"     desktop/catalog-listing

               "desktop/locked"  (constantly false)
               "desktop/running" #(:current (desktop/current-apps-state))
               "desktop/catalog" desktop/catalog-listing

               "audio"    queries/audio-status
               "keyboard" queries/keyboard-status
               "net"      (constantly {:ip "192.168.1.196"})

               "system/hostname" #(get (control/settings) [:system :hostname])
               "system/timezone" #(get (control/settings) [:system :timezone])
               "system/clock-ms" #(System/currentTimeMillis)

               "monitor/uptime-minutes" (constantly 42)
               "monitor/messages"       (constantly [])}})

     (routes/queries
      {:base  "query"
       :nodes {"settings" control/settings-records-tree}}))})
