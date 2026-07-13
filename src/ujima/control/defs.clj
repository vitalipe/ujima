(ns ujima.control.defs)


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


(def settings [{:key     [:system :hostname]
                :doc     "LAN hostname for this machine (single label, not an FQDN)"
                ;; must match the baked /etc/hostname (tools base.clj) — a mismatch makes the
                ;; hostname converge rename every machine on every boot (overlayroot resets /etc)
                :default "ujimaos"
                :scopes  #{:device}}

               {:key     [:system :timezone]
                :doc     "IANA timezone (tzdata name)"
                :default "Africa/Dar_es_Salaam"
                :scopes  #{:device}}

               {:key     [:keyboard :layout]
                :doc     "XKB layout code (e.g. \"us\", \"tz\", \"il\")"
                :default "us"
                :scopes  #{:device :session :activity}}

               {:key     [:keyboard :available-layouts]
                :doc     "XKB layout codes offered in the layout switcher UI (data-only: no converge handler consumes it)"
                :default ["us" "tz"]
                :scopes  #{:device}}

               {:key     [:audio :active]
                :doc     "Active output class (:usb | :hdmi, nil = none); written by the
                          device-event policy in the agent, enforced as the default sink"
                :default nil
                :scopes  #{:device :session :activity}}

               {:key     [:audio :muted]
                :doc     "Audio muted? (machine-wide: asserted on every present sink)"
                :default false
                :scopes  #{:session :activity}}

               {:key     [:audio :usb :volume]
                :doc     "USB headphones volume 0-100 (low default = ear-safe first plug)"
                :default 40
                :scopes  #{:device :session :activity}}

               {:key     [:audio :hdmi :volume]
                :doc     "HDMI output volume 0-100"
                :default 70
                :scopes  #{:device :session :activity}}])
