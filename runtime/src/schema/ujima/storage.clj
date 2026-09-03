(ns schema.ujima.storage
  "The stick layout: what a mounted partition is scanned for — one fixed filename per
   token type, all under one directory. Storage reports hits and never interprets them.")


(def dir "ujima")

(def markers {"circle.json"  :circle/secret
              "install.json" :ujima/pack})


;; what a token's parsed content must look like — open maps, extras welcome
(def shapes
  {:circle/secret [:map [:key [:string {:min 1}]]]
   :ujima/pack    [:map
                   [:pack  [:string {:min 1}]]
                   [:label {:optional true} :string]]})
