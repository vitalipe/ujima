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


(def ^:private window
  "What this app's windows look like — the X properties i3 reports, as an author states them:
   :class is WM_CLASS's res_class, :instance its res_name. Every key given must hold, so a
   suite whose editors share one res_class is still told apart by the name each was launched
   under. At least one key: an empty map would describe every window on the screen."
  [:and [:map {:closed true}
         [:class    {:optional true} [:string {:min 1}]]
         [:instance {:optional true} [:string {:min 1}]]]
        [:fn {:error/message "names no property — that would describe every window on screen"}
         seq]])

(def exec
  (kind [:kind   [:= :exec]]
        [:exec   [:vector {:min 1} :string]]
        [:window {:optional true} window]))

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
