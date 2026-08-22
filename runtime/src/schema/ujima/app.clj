(ns schema.ujima.app
  "What an app.edn may say — the contract an app dir ships, as malli data. :kind picks the
   shape. The scan adds :id :dir :icon (and :class for the web kinds), the session adds :env —
   none of those are authored. Only shape lives here: whether :entry or a relative argv[0]
   exists on disk is the loader's check.")


(def categories [:learn :explore :office :create :code :system])

(def category
  (into [:enum {:error/message "not a category ujima knows"}] categories))


(def ^:private common
  [[:label    [:string {:min 1}]]
   [:category {:optional true} category]
   [:hidden   {:optional true} :boolean]])

(defn- kind [& entries]
  (into [:map {:closed true}] (concat common entries)))


(def exec
  (kind [:kind  [:= :exec]]
        [:exec  [:vector {:min 1} :string]]
        [:class {:optional true} [:string {:min 1}]]))

(def web-app
  (kind [:kind  [:= :web-app]]
        [:entry [:string {:min 1}]]
        [:port  [:int {:min 1 :max 65535}]]))

(def link
  (kind [:kind [:= :link]]
        [:url  [:re {:error/message "must be an http(s) url"} #"^https?://\S+$"]]))


(def spec
  [:multi {:dispatch :kind}
   [:exec    exec]
   [:web-app web-app]
   [:link    link]])
