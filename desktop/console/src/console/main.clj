(ns console.main
  "Circle and Setup over the real circle. The token arrives in the environment,
   never argv. UJIMA_SELF is the machine we administer — its subnet is the one swept."
  (:require [console.circle :as circle]
            [console.http  :as http]))


(def ^:private ui-root "ui")

(def ^:private ui-roots
  {:console ui-root
   :circle  (str ui-root "/circle")
   :setup   (str ui-root "/setup")})


(defn -main [& _]
  (let [key (System/getenv "UJIMA_CIRCLE_TOKEN")]
    (when-not key
      (println "console: no UJIMA_CIRCLE_TOKEN — nothing can be signed, the circle stays empty"))
    (circle/init! {:key key :self-addr (or (System/getenv "UJIMA_SELF") "127.0.0.1")})
    (http/init! {:ui-roots ui-roots})
    (println "console: http://127.0.0.1:1338/   circle /circle/   setup /setup/")
    @(promise)))
