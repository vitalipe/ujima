(ns ujima.events.token
  "Admin-token policy: does any mounted usb storage carry the ujima admin
   token? Decisions only — the watching is linux.usb/watch-storage!, the
   listener thread is ujima.events. The actions are idempotent ensure-style:
   storage events fire on any mount change, token-relevant or not."
  (:require [babashka.fs :as fs]
            [ujima.log   :as log]))


(def ^:private token-file ".ujima-admin-token")


(defn- find-token
  "The token file's path on one of `mounts`, nil when absent."
  [mounts]
  (->> mounts
       (map #(fs/path % token-file))
       (filter fs/exists?)
       (first)))


(defn on-storage-changed!
  "Handle one storage event: ensure the admin surface matches token presence.
   Returns the token path (or nil) — the decision, for tests."
  [{:keys [mounts]}]
  (if-let [token (find-token mounts)]
    (do (log/info "admin token present, open admin app!" {:token (str token)})
        (str token))
    (do (log/info "admin token missing, close admin app!")
        nil)))
