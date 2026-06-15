(ns ujima.control.defs)


(def scopes [{:key     :device
              :doc     "Persistent per-device config and policy"
              :persist? true}

             {:key     :session
              :doc     "Temporary session-level config and policy. Cleared when the user/session resets"
              :persist? false}

             {:key     :activity
              :doc     "Short-lived activity override, such as presentation, exam, lesson, focus, or demo mode"
              :persist? false}])


(def settings [{:key     :system/hostname
                :doc     "LAN hostname for this machine (single label, not an FQDN)"
                :default "ujima"
                :scopes  #{:device}}
              
               {:key     :system/timezone
                :doc     "IANA timezone (tzdata name)"
                :default "Africa/Dar_es_Salaam"
                :scopes  #{:device}}
                              
               {:key     :keyboard/layout
                :doc     "XKB layout codes"
                :default "en"
                :scopes  #{:device :session :activity}}

               {:key     :audio/muted
                :doc     "Audio muted?"
                :default false
                :scopes  #{:session :activity}}

               {:key     :audio/volume
                :doc     "volume 0-100"
                :default 50
                :scopes  #{:session :activity}}])
