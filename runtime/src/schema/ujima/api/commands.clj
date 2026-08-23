(ns schema.ujima.api.commands
  "The verbs /api/commands accepts — the frozen v1 contract. One entry per verb: what it does
   and the params it takes. Handlers belong to the tier that serves them, so a fake fleet can
   share this contract without the daemon's dependencies."
  (:require [schema.ujima.settings :as settings]))


;; :device is config, not a moment
(def runtime-scope [:enum :session :activity])
(def scope         [:enum :device :session :activity])


;; ex-info {:error kw} these verbs raise -> status (:request/malformed is lib.http's)
(def errors
  {:audio/no-output         409
   :keyboard/unknown-layout 409
   :settings/unknown        404
   :app/unknown-app         404
   :app/bad-url             400})


(def commands
  {"app/open"     {:doc    "Open an app by catalog id; :mode :solo opens it soloed."
                   :params [:map [:app [:string {:min 1}]] [:mode {:optional true} [:enum :multi :solo]]]}

   "app/unsolo"   {:doc    "Leave solo mode."}

   "app/switch"   {:doc    "Focus an app that is already open."
                   :params [:map [:app [:string {:min 1}]]]}

   "app/close"    {:doc    "Close the focused app."}

   "app/home"     {:doc    "Go to the home workspace."}

   "app/open-url" {:doc    "Open a URL in the Web app."
                   :params [:map [:url [:string {:min 1}]]]}

   ;; not settings/**: the output is resolved at write time
   "audio/volume" {:doc    "Set the ACTIVE output's volume; the effect clamps to 0-100."
                   :params [:map [:scope runtime-scope] [:value [:or :int :double]]]}

   ;; not settings/**: narrows against another setting's value
   "keyboard/layout" {:doc    "Set the layout; only codes in available-layouts are accepted."
                      :params [:map [:scope runtime-scope] [:layout [:string {:min 1}]]]}

   "settings/**"  {:doc    "Set any setting by its path; the def decides the legal scopes and values."
                   :params [:map [:path [:vector :keyword]] [:scope scope] [:value :any]]}

   ;; a clear carries no body: the URL is the whole operation
   "clear/:scope/**" {:doc    "Release SCOPE's hold on the setting at path — with no path, everything it holds. Runtime scopes only."
                      :params [:map [:scope runtime-scope] [:path [:vector :keyword]]]}

   ;; the timezone rides along so one call moves a machine's whole clock; the setting's own
   ;; closed list is the shape, so an unknown zone dies at the edge and no handler runs
   "system/clock"    {:doc    "Set the wall clock to EPOCH (ms); records it as the new floor. A timezone, when given, is applied first."
                      :params [:map [:epoch [:int {:min 0}]] [:timezone {:optional true} settings/timezone]]}

   "desktop/lock"    {:doc "Lock this machine's screen."}

   "desktop/unlock"  {:doc "Unlock this machine's screen."}

   "system/restart"  {:doc "Reboot this machine."}

   "system/poweroff" {:doc "Power this machine off."}})
