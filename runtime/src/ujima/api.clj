(ns ujima.api
  "The /api tier of the machine edge (lib.http tries it): the core resource
   API over control — the stable(ish) surface agents and remote ops consume.
   Transport only: parse, run at most ONE command, respond with a query —
   writes answer with the fresh resource, as data (the edge owns the wire
   form). The v1 query/commands families grow here."
  (:require [ujima.control.commands :as commands]
            [ujima.control.queries  :as queries]))


;; this tier's ex-info vocabulary -> status, merged into the edge at init
(def error-status
  {:audio/no-output         409
   :keyboard/unknown-layout 409})


(defn handler [req parts body]
  (case [(:request-method req) parts]
    [:get  ["api" "audio"]]                     {:status 200 :body (queries/audio-status)}
    [:get  ["api" "input" "keyboard"]]          {:status 200 :body (queries/keyboard-status)}
    [:post ["api" "audio" "volume"]]            (do (commands/change-current-volume! (:value body))
                                                    {:status 200 :body (queries/audio-status)})
    [:post ["api" "audio" "mute"]]              (do (commands/change-mute! (:muted body))
                                                    {:status 200 :body (queries/audio-status)})
    [:post ["api" "audio" "output"]]            (do (commands/change-active-output! (:output body))
                                                    {:status 200 :body (queries/audio-status)})
    [:post ["api" "input" "keyboard" "layout"]] (do (commands/change-keyboard-layout! (:layout body))
                                                    {:status 200 :body (queries/keyboard-status)})
    nil))
