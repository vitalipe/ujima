(ns schema.ujima.settings
  "The settings vocabulary — pure data, one entry = the whole description:
   key, doc, default, scopes, :secret?, and the value :shape (malli, as data).
   Entries require nothing outside the data plane; machinery and enforcement
   live outside it."
  (:require [schema.build.timezones   :as tz]
            [schema.build.xkb-layouts :as xkb]))


;; persisted scope-file format version — bump when the stored shape changes
;; (schema 1 = flat :settings map, path-vector keys)
(def schema 1)


(def scopes [{:key     :circle
              :doc     "The circle this machine belongs to — the same on every member; joined at setup"
              :persist? true}

             {:key     :device
              :doc     "Persistent per-device config and policy"
              :persist? true}

             {:key     :session
              :doc     "Temporary session-level config and policy. Cleared when the user/session resets"
              :persist? false}

             {:key     :activity
              :doc     "Short-lived activity override, such as presentation, exam, lesson, focus, or demo mode"
              :persist? false}])


;; public: /api's clock verb narrows its param to this same closed list
(def timezone
  (into [:enum {:error/message "not a timezone ujima knows"}] tz/names))

(def ^:private xkb-layout
  (into [:enum {:error/message "not an XKB layout ujima knows"}] xkb/names))


(def settings [{:key     [:circle :name]
                :doc     "The circle's label"
                :default "UjimaOS"
                :scopes  #{:circle}
                :shape   [:re {:error/message "1-32 letters, numbers, spaces, dashes or underscores, starting and ending with a letter or number"}
                          #"^[A-Za-z0-9]([A-Za-z0-9 _-]{0,30}[A-Za-z0-9])?$"]}

               {:key     [:circle :token]
                :doc     "Shared admin token — every machine in the circle holds the same one"
                :default "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
                :secret? true
                :scopes  #{:circle}
                :shape   [:re {:error/message "must be 64 hex characters"} #"^[0-9a-f]{64}$"]}

               {:key     [:system :hostname]
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

               {:key     [:system :clock :epoch-floor]
                :doc     "Time never converges below this (epoch ms) — the software RTC; written by the clock heartbeat and the clock verb"
                :default 0
                :scopes  #{:device}
                :shape   [:int {:min 0}]}

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

               {:key     [:network :wifi :mode]
                :doc     "What the radio does: :peer joins the wifi network below, :off keeps it down"
                :default :peer
                :scopes  #{:circle :device}
                :shape   [:enum :peer :off]}

               {:key     [:network :wifi :essid]
                :doc     "The wifi network to join; circle-wide, a device may override"
                :default "ujima-default-circle"
                :scopes  #{:circle :device}
                ;; narrower than the spec's any-32-bytes: survives a keyfile and a UI untouched
                :shape   [:re {:error/message "1-32 printable characters, not starting or ending with a space"}
                          #"^[!-~]([ -~]{0,30}[!-~])?$"]}

               {:key     [:network :wifi :psk]
                :doc     "The network's WPA passphrase (nil = open network)"
                :default nil
                :secret? true
                :scopes  #{:circle :device}
                :shape   [:re {:error/message "8-63 printable characters, or 64 hex digits"}
                          #"^([ -~]{8,63}|[0-9a-fA-F]{64})$"]}

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
