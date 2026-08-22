(ns ujima.linux.net.wifi
  (:require [clojure.string   :as str]
            [babashka.fs      :as fs]
            [lib.shell        :refer [$!]]
            [ujima.linux.sudo :refer [sudo$! sudo$?]]))


;; One NetworkManager profile, `ujima`, is all ujimad owns — one thing to reconcile, and a
;; human's profiles are never touched. It lives in the overlay's tmpfs upper and is gone at
;; boot; converge re-asserts it. The regulatory domain stays at world: a client follows the
;; AP's channel, and any country code only narrows what a client may join.


(def ^:private profile "ujima")
(def ^:private keyfile (str "/etc/NetworkManager/system-connections/" profile ".nmconnection"))
(def ^:private uuid    (str (java.util.UUID/nameUUIDFromBytes (.getBytes "ujima-wifi" "UTF-8"))))


;; GKeyFile: `\` escapes, edge spaces are `\s`
(defn- escape [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace #"^ +| +$" #(str/replace % " " "\\s"))))

(defn- unescape [s]
  (str/replace s #"\\(.)" (fn [[_ c]] (case c "s" " " "n" "\n" "t" "\t" c))))


(defn keyfile-text
  "The NM keyfile for {:ssid :psk}; no :psk = an open network."
  [{:keys [ssid psk]}]
  (str "[connection]\n"
       "id=" profile "\n"
       "uuid=" uuid "\n"
       "type=wifi\n"
       "autoconnect=true\n"
       "autoconnect-retries=0\n"          ; NM's default gives up after 4 and backs off minutes
       "\n[wifi]\n"
       "mode=infrastructure\n"
       "ssid=" (escape ssid) "\n"
       (when psk
         (str "\n[wifi-security]\n"
              "auth-alg=open\n"
              "key-mgmt=wpa-psk\n"
              "psk=" (escape psk) "\n"))
       "\n[ipv4]\nmethod=auto\n"
       "\n[ipv6]\nmethod=auto\n"))


(defn parse-keyfile
  "{:ssid :psk} out of keyfile text, nil when it carries no ssid."
  [text]
  (let [fields (->> (str/split-lines text)
                    (map str/trim)
                    (keep #(when-let [[_ k v] (re-matches #"([\w-]+)=(.*)" %)] [k (unescape v)]))
                    (into {}))]
    (when-let [ssid (get fields "ssid")]
      {:ssid ssid :psk (get fields "psk")})))


(defn radio []
  (= "enabled" ($! nmcli radio wifi)))


(defn radio! [on?]
  (sudo$! nmcli radio wifi [(if on? "on" "off")])
  (radio))


(defn network
  "The {:ssid :psk} ujimad's profile holds, nil when there is none."
  []
  (let [{:keys [ok? out]} (sudo$? cat [keyfile])]
    (when ok? (parse-keyfile out))))


(defn join!
  "Write the profile and (re)activate it — a loaded keyfile only takes effect on the next
   activation. The activation is not awaited: out of range, autoconnect keeps trying."
  [desired]
  (let [tmp (fs/create-temp-file {:prefix "ujima-wifi" :posix-file-permissions "rw-------"})]
    (try
      (spit (str tmp) (keyfile-text desired))
      (sudo$! install -m 600 -o root -g root [(str tmp)] [keyfile])
      (finally (fs/delete-if-exists tmp))))
  (sudo$! nmcli connection load [keyfile])
  (sudo$? nmcli --wait 0 connection up [profile])
  (network))
