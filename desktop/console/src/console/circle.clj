(ns console.circle
  "Circle's slice of the console edge: the verb whitelist the panel POSTs,
   per-verb arg validation, and the one composed view it polls."
  (:require [console.fleet :as fleet]
            [console.jobs  :as jobs]))


(defn- malformed! [message]
  (throw (ex-info message {:error :request/malformed})))


(def verb-routes
  {["circle" "mute"]      :mute
   ["circle" "unmute"]    :unmute
   ["circle" "release"]   :release
   ["circle" "volume"]    :volume
   ["circle" "close-app"] :close-app
   ["circle" "open-app"]  :open-app
   ["circle" "open-url"]  :open-url
   ["circle" "lock"]      :lock
   ["circle" "unlock"]    :unlock
   ["circle" "restart"]   :restart
   ["circle" "poweroff"]  :poweroff})

(defn- targets [body]
  (let [ts (:targets body)]
    (when (or (not (sequential? ts)) (empty? ts) (not-every? string? ts))
      (malformed! "targets must be a non-empty list of peer ids"))
    (vec ts)))

(defn- verb-args [verb body]
  (case verb
    :open-app (if (string? (:app body))
                {:app (:app body)}
                (malformed! "open-app needs an :app id"))
    :open-url (if (and (string? (:url body)) (seq (:url body)))
                {:url (:url body)}
                (malformed! "open-url needs a :url"))
    :volume   (if (number? (:value body))
                {:value (-> (:value body) double Math/round (max 0) (min 100))}
                (malformed! "volume needs a :value 0-100"))
    {}))


(defn act! [verb body]
  (jobs/act! fleet/send! :circle verb (targets body) (verb-args verb body)))

(defn view []
  {:schema 1
   :self   (fleet/self)
   :peers  (fleet/peers)
   :scan   (fleet/scan)
   :panel  {:action (jobs/active-action :circle)}})
