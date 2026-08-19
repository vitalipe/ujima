(ns ujima.api
  "The /api tier: query/machine a node per source, query/settings one node over control's
   records, commands the verbs. The contract — docs, params, errors, reply shapes — is pure
   data in schema.ujima.api.*; this ns binds an effect to each verb."
  (:require [schema.ujima.api.commands :as defs]
            [ujima.api.routes       :as routes]
            [ujima.control          :as control]
            [ujima.control.queries  :as queries]
            [ujima.control.commands :as effects]
            [ujima.desktop.app      :as desktop]
            [ujima.linux.devicetree :as devicetree]
            [ujima.linux.disk       :as disk]
            [ujima.linux.net        :as net]
            [ujima.linux.system     :as system]))


;; ── the verbs ───────────────────────────────────────────────────────────────

(defn- with-handlers
  "Each spec bound to this tier's handler. A spec with no handler, or a handler with no
   spec, fails here — at load — rather than at request time."
  [specs handlers]
  (assert (= (set (keys specs)) (set (keys handlers)))
          (str "command table drift — spec with no handler: " (sort (remove handlers (keys specs)))
               ", handler with no spec: "                     (sort (remove specs (keys handlers)))))
  (into {} (for [[path spec] specs] [path (assoc spec :handler (handlers path))])))


(def commands
  (with-handlers defs/commands
    {"app/open"     (fn [{:keys [app]}] (desktop/run! (keyword app)))
     "app/switch"   (fn [{:keys [app]}] (desktop/switch-to! (keyword app)))
     "app/close"    (fn [_] (desktop/close-focused!))
     "app/home"     (fn [_] (desktop/go-home!))
     "app/open-url" (fn [{:keys [url]}] (desktop/open-url! url))

     "audio/volume"    (fn [{:keys [scope value]}]  (effects/change-current-volume! value scope))
     "keyboard/layout" (fn [{:keys [scope layout]}] (effects/change-keyboard-layout! layout scope))

     "settings/**"     (fn [{:keys [path value scope]}] (effects/change-setting! path value scope))

     "clear/:scope/**" (fn [{:keys [scope path]}]
                         (if (seq path)
                           (effects/clear-setting! path scope)
                           (effects/clear-scope! scope)))

     ;; timezone first: a bad zone is refused before the clock moves
     "system/clock"    (fn [{:keys [epoch timezone]}]
                         (when timezone
                           (effects/change-setting! [:system :timezone] timezone :device))
                         (system/clock! epoch)
                         (effects/change-setting! [:system :clock :epoch-floor] epoch :device))

     "system/restart"  (fn [_] (system/reboot!))
     "system/poweroff" (fn [_] (system/shutdown!))}))


;; ── the routes ──────────────────────────────────────────────────────────────

(defn endpoints
  [{:keys [version id gate] system-disk :disk}]
  (assert gate "no :gate — pass identity to serve unauthenticated")

  {:errors defs/errors
   :routes

   (merge

     ;; gated
     (gate
      (routes/commands
       {:base     "commands"
        :commands commands}))

     (gate
      (routes/queries
       {:base  "query"
        :nodes {"settings" #(-> (control/settings)
                              (queries/public-settings)
                              (queries/settings->tree))}}))
     ;; open
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

               "audio"    #(queries/audio-status (control/settings))

               "keyboard" (fn [] (let [s (control/settings)]
                                   {:layout            (:effective (get s [:keyboard :layout]))
                                    :available-layouts (:effective (get s [:keyboard :available-layouts]))}))

               "net"      (fn [] (let [facts (net/interface-facts)]
                                   {:ip         (net/lan-ip facts)
                                    :interfaces facts}))

               "system/hostname" #(:effective (control/setting [:system :hostname]))
               "system/timezone" #(:effective (control/setting [:system :timezone]))
               "system/clock-ms" #(System/currentTimeMillis)

               "monitor/uptime-minutes" system/uptime-minutes
               "monitor/messages"       (constantly [])}}))})
