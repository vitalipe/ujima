(ns schema.ujima.api.query
  "The shapes /api/query answers with — the frozen v1 contract. Maps are OPEN: a new key is legal,
   a renamed or retyped one fails the contract test.")


;; one def, so a reply and the machine tree cannot disagree
(def audio
  [:map
   [:volume [:maybe [:int {:min 0, :max 100}]]]
   [:muted  :boolean]
   [:output [:maybe [:enum :usb :hdmi]]]])


;; :scopes holds the allowed ones only — the keys are the write whitelist
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
