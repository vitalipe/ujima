(ns schema.ujima.settings
  "The settings vocabulary — pure data, one entry = the whole description:
   key, doc, default, scopes, and the value :shape (malli, as data).
   src/schema is the data plane: entries require nothing outside it
   (schema.build.* = the generated pins); machinery and enforcement live
   outside (control merges scopes; the HTTP gate will conform :shape)."
  (:require [schema.build.timezones   :as tz]
            [schema.build.xkb-layouts :as xkb]))


;; persisted scope-file format version — bump when the stored shape changes
;; (schema 1 = flat :settings map, path-vector keys)
(def schema 1)


(def scopes [{:key     :device
              :doc     "Persistent per-device config and policy"
              :persist? true}

             {:key     :session
              :doc     "Temporary session-level config and policy. Cleared when the user/session resets"
              :persist? false}

             {:key     :activity
              :doc     "Short-lived activity override, such as presentation, exam, lesson, focus, or demo mode"
              :persist? false}])


(def ^:private timezone
  (into [:enum {:error/message "not a timezone ujima knows"}] tz/names))

(def ^:private xkb-layout
  (into [:enum {:error/message "not an XKB layout ujima knows"}] xkb/names))


(def settings [{:key     [:system :hostname]
                :doc     "LAN hostname for this machine (single label, not an FQDN)"
                ;; nil = keep the baked /etc/hostname (tools base.clj). A set value renames at
                ;; converge — every boot, overlayroot resets /etc (harmless: X runs -ac); clearing
                ;; it reverts only on reboot
                :default nil
                :scopes  #{:device}
                ;; anchored: malli's :re is re-find, not re-matches
                :shape   [:re {:error/message "hostname must be 1-16 letters, numbers or dashes"}
                          #"^[A-Za-z0-9-]{1,16}$"]}

               {:key     [:system :timezone]
                :doc     "IANA timezone (tzdata name)"
                :default "Africa/Dar_es_Salaam"
                :scopes  #{:device}
                :shape   timezone}

               {:key     [:keyboard :layout]
                :doc     "XKB layout code (e.g. \"us\", \"tz\", \"il\")"
                :default "us"
                :scopes  #{:device :session :activity}
                :shape   xkb-layout}

               {:key     [:keyboard :available-layouts]
                :doc     "XKB layout codes offered in the layout switcher UI (data-only: no converge handler consumes it)"
                :default ["us" "tz"]
                :scopes  #{:device}
                :shape   [:sequential {:min 1} xkb-layout]}

               {:key     [:audio :active]
                :doc     "Active output class (:usb | :hdmi, nil = none); written by the
                          device-event policy in ujimad, enforced as the default sink"
                :default nil
                :scopes  #{:device :session :activity}
                :shape   [:enum :usb :hdmi]}

               {:key     [:audio :muted]
                :doc     "Audio muted? (machine-wide: asserted on every present sink)"
                :default false
                :scopes  #{:session :activity}
                :shape   :boolean}

               {:key     [:audio :usb :volume]
                :doc     "USB headphones volume 0-100 (low default = ear-safe first plug)"
                :default 40
                :scopes  #{:device :session :activity}
                :shape   [:int {:min 0 :max 100}]}

               {:key     [:audio :hdmi :volume]
                :doc     "HDMI output volume 0-100"
                :default 70
                :scopes  #{:device :session :activity}
                :shape   [:int {:min 0 :max 100}]}])
