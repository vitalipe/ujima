(ns ujima.api
  "The /api tier: query/machine a node per source, query/settings one node over control's
   records, commands the verbs below. A verb keeps :doc, :params and :handler together; the
   query side's reply shapes are pure data, in schema.ujima.api.query."
  (:require [ujima.api.routes       :as routes]
            [ujima.control          :as control]
            [ujima.control.queries  :as queries]
            [ujima.control.commands :as effects]
            [ujima.desktop.app      :as desktop]
            [ujima.linux.devicetree :as devicetree]
            [ujima.linux.disk       :as disk]
            [ujima.linux.net        :as net]
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

   ;; a clear carries no body: the URL is the whole operation
   "clear/:scope/**" {:doc     "Release SCOPE's hold on the setting at path — with no path, everything it holds. Runtime scopes only."
                      :params  [:map [:scope runtime-scope] [:path [:vector :keyword]]]
                      :handler (fn [{:keys [scope path]}]
                                 (if (seq path)
                                   (effects/clear-setting! path scope)
                                   (effects/clear-scope! scope)))}

   "system/clock"    {:doc     "Set the wall clock to EPOCH (ms); records it as the new floor."
                      :params  [:map [:epoch [:int {:min 0}]]]
                      :handler (fn [{:keys [epoch]}]
                                 (system/clock! epoch)
                                 (effects/change-setting! [:system :clock :epoch-floor] epoch :device))}

   "system/restart"  {:doc     "Reboot this machine."
                      :handler (fn [_] (system/reboot!))}

   "system/poweroff" {:doc     "Power this machine off."
                      :handler (fn [_] (system/shutdown!))}})


;; ── the routes ──────────────────────────────────────────────────────────────

;; :disk = ujima-disk-info, queried once at boot; only the space numbers are live
(defn endpoints [{:keys [version id] system-disk :disk}]
  
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
               "device"   (fn [] {:serial (devicetree/serial)
                                  :model  (devicetree/model)})
               "image"    (constantly {:version version})
               "disk"     (fn [] {:type     (:type system-disk)
                                  :slot     (:boot-slot system-disk)
                                  :storage  (disk/device->space (:storage system-disk))
                                  :settings (disk/device->space (:config system-disk))})
               
               "apps"     desktop/catalog-listing

               "desktop/locked"  (constantly false)
               "desktop/running" #(:current (desktop/current-apps-state))
               "desktop/catalog" desktop/catalog-listing

               "audio"    #(queries/audio-status    (control/settings))
               "keyboard" #(queries/keyboard-status (control/settings))
               "net"      (fn [] (let [facts (net/interface-facts)]
                                   {:ip         (net/lan-ip facts)
                                    :interfaces facts}))

               "system/hostname" #(get (control/settings) [:system :hostname])
               "system/timezone" #(get (control/settings) [:system :timezone])
               "system/clock-ms" #(System/currentTimeMillis)

               "monitor/uptime-minutes" system/uptime-minutes
               "monitor/messages"       (constantly [])}})

     (routes/queries
      {:base  "query"
       :nodes {"settings" control/settings-records-tree}}))})
