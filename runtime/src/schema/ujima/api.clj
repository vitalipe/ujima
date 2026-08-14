(ns schema.ujima.api
  "The wire contract as data — SHAPE only, never execution and never routing:
   commands are keyed by their path (the coordinate; the api tier decides where
   it mounts and what it does), and their :params is one map over everything
   the caller sends — slug, query and body merged, conformed at the gate by the
   same humanize machinery as settings writes. The settings-write family is NOT
   described here — schema.ujima.settings owns it entirely; a second
   description would be a second truth.

   Maps are OPEN on purpose: additive-only by construction — a new reply key
   is legal, a renamed or retyped one fails the contract test.")


;; ── the error vocabulary: ex-info {:error kw} -> status ─────────────────────
;; (:request/malformed 400 is transport-level, owned by lib.http)

(def errors
  {:audio/no-output         409
   :keyboard/unknown-layout 409
   :settings/unknown        404
   :app/unknown-app         404
   :app/bad-url             400})


;; ── the shared fragments ────────────────────────────────────────────────────
;; a reply that mirrors a slice of the machine tree IS that slice — one def, so
;; "POST volume answers what GET machine/audio shows" is a fact, not a habit

;; whose write this is: the user's own session, or a coordinated activity
;; driven from elsewhere. :device is config, not a moment, so never here
(def runtime-scope [:enum :session :activity])

;; a settings write may name any scope — which ones a given setting accepts is
;; the def's business, checked where the path is known
(def scope [:enum :device :session :activity])


(def audio
  [:map
   [:volume [:maybe [:int {:min 0, :max 100}]]]
   [:muted  :boolean]
   [:output [:maybe [:enum :usb :hdmi]]]])


;; ── commands: the verb vocabulary, keyed by path ────────────────────────────
;; subject first, then the verb or the field — the same vocabulary the machine
;; tree reads by, so a write and a read of one thing share a coordinate.
;; :params  malli, over (merge body query slug); :scope says whose write it is,
;;          the user's session or a coordinated activity
;; :reply   a shape -> 200 with the handler's body; absent -> 202 {}

(def commands
  {"app/open"     {:doc    "Open an app by catalog id."
                   :params [:map [:app [:string {:min 1}]]]}

   "app/switch"   {:doc    "Focus an app that is already open."
                   :params [:map [:app [:string {:min 1}]]]}

   "app/close"    {:doc "Close the focused app."}
   "app/home"     {:doc "Go to the home workspace."}

   "app/open-url" {:doc    "Open a URL in the Web app."
                   :params [:map [:url [:string {:min 1}]]]}

   "audio/volume" {:doc    "Set the ACTIVE output's volume; the effect clamps to 0-100."
                   :params [:map [:scope runtime-scope] [:value [:or :int :double]]]}

   "audio/mute"   {:doc    "Mute or unmute; a desired state, not a toggle."
                   :params [:map [:scope runtime-scope] [:muted :boolean]]}

   "audio/output" {:doc    "Select the active output class; null = none."
                   :params [:map [:scope runtime-scope] [:output [:maybe [:enum :usb :hdmi]]]]}

   "keyboard/layout" {:doc    "Set the layout; only codes in available-layouts are accepted."
                      :params [:map [:scope runtime-scope] [:layout [:string {:min 1}]]]}

   "settings/**"  {:doc    "Set any setting by its path; the def decides the legal
                            scopes and values."
                   :params [:map [:path [:vector :keyword]] [:scope scope] [:value :any]]}

   "system/restart"  {:doc "Reboot this machine."}
   "system/poweroff" {:doc "Power this machine off."}})


;; ── query trees: the reply shapes (the frozen v1 contract) ──────────────────

;; every leaf of query/settings is this record; :scopes holds the ALLOWED
;; scopes only — its keys double as the write whitelist
(def settings-record
  [:map
   [:effective :any]
   [:via       [:enum :device :session :activity :default]]
   [:default   :any]
   [:scopes    [:map-of [:enum :device :session :activity] :any]]])


(def machine
  (let [app-entry       [:map [:id :keyword] [:label [:maybe :string]]]
        desktop-entry   [:map [:id :keyword] [:label [:maybe :string]] [:category [:maybe :keyword]]]
        partition-space [:map [:total-mb :int] [:free-mb :int]]]

    [:map
     [:schema   [:= 1]]
     [:id       [:maybe :string]] ;; FIXME-nil until the card-stamped id lands
     [:device   [:map [:serial [:maybe :string]] [:model [:maybe :string]]]]
     [:image    [:map [:version [:maybe :string]]]]
     [:disk     [:map [:type     [:maybe :keyword]]
                      [:slot     [:maybe :keyword]]
                      [:storage  [:maybe partition-space]]
                      [:settings [:maybe partition-space]]]]
     [:apps     [:vector app-entry]]
     [:desktop  [:map [:locked  [:maybe :boolean]]
                      [:running [:maybe desktop-entry]]
                      [:catalog [:vector desktop-entry]]]]
     [:audio    audio]
     [:keyboard [:map [:layout :string] [:layouts [:vector :string]]]]
     [:net      [:map [:ip [:maybe :string]]]]
     [:system   [:map [:hostname [:maybe :string]]
                      [:timezone [:maybe :string]]
                      [:clock-ms :int]]]
     [:monitor  [:map [:uptime-minutes [:maybe :int]]
                      [:messages [:vector [:map [:type :keyword] [:id :keyword] [:label :string]]]]]]]))
