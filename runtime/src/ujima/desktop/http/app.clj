(ns ujima.desktop.http.app
  "The /ui/apps stream — a converge target on app's (next prv) projection.
   Cold until the first i3 event, so it declares what to greet with."
  (:require [lib.http.ndjson :as ndjson]))


(defonce ^:private apps (ndjson/topic! :ui/apps {:apps [] :current nil}))


(defn converge! [next _prv] (ndjson/publish! apps next))

(defn stream [req] (ndjson/subscribe! apps req))
