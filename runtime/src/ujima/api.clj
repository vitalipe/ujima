(ns ujima.api
  "The /api tier — real wherever a reader exists, canned where none does yet.

     GET  /api/query/machine/**   a node per source; some still canned
     GET  /api/query/settings/**  one node — control's records, live
     POST /api/commands/…         the contract's verbs, gated and real"
  (:require [ujima.api.routes       :as routes]
            [ujima.control          :as control]
            [ujima.control.queries  :as queries]
            [ujima.control.commands :as effects]
            [ujima.desktop.app      :as desktop]
            [ujima.linux.system     :as system]
            [schema.ujima.api       :as contract]))


;; ── the routes ──────────────────────────────────────────────────────────────


(def ^:private commands
  {"app/open"     {:handler (fn [{:keys [app]}] (desktop/run! (keyword app)))}
   "app/switch"   {:handler (fn [{:keys [app]}] (desktop/switch-to! (keyword app)))}
   "app/close"    {:handler (fn [_]             (desktop/close-focused!))}
   "app/home"     {:handler (fn [_]             (desktop/go-home!))}
   "app/open-url" {:handler (fn [{:keys [url]}] (desktop/open-url! url))}

   "audio/volume"    {:handler (fn [{:keys [scope value]}]  (effects/change-current-volume! value scope))}
   "audio/mute"      {:handler (fn [{:keys [scope muted]}]  (effects/change-mute! muted scope))}
   "audio/output"    {:handler (fn [{:keys [scope output]}] (effects/change-active-output! output scope))}
   "keyboard/layout" {:handler (fn [{:keys [scope layout]}] (effects/change-keyboard-layout! layout scope))}

   "settings/**"     {:handler (fn [{:keys [path value scope]}] (effects/change-setting! path value scope))}

   "system/restart"  {:handler (fn [_] (system/reboot!))}
   "system/poweroff" {:handler (fn [_] (system/shutdown!))}})


(def endpoints
  {:errors contract/errors
   :routes
   (merge
     (routes/commands
      {:base     "commands"
       :commands (merge-with merge contract/commands commands)})

     (routes/queries
      {:base  "query/machine"
       :nodes {"schema"   (constantly 1)
               "id"       (constantly "mock-00000001")
               "device"   (constantly {:serial "10000000deadbeef"
                                       :model  "Raspberry Pi 500 Rev 1.0"})
               "image"    (constantly {:version "0.9.0"})
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

