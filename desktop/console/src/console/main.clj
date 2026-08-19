(ns console.main
  "Dev entry: the console (Circle + Setup panels) over the file-backed mock
   fleet."
  (:require [console.mock :as mock]
            [console.http :as http]))


(def ^:private seed-path "dev/world.edn")
(def ^:private live-path "tmp/world.edn")

(def ^:private ui-root "ui")

(def ^:private ui-roots
  {:console ui-root
   :circle  (str ui-root "/circle")
   :setup   (str ui-root "/setup")})


(defn -main [& _]
  (mock/seed! seed-path live-path)
  (http/init! {:ui-roots ui-roots :transport (mock/transport live-path)})
  (println "console: mock fleet at http://localhost:1338 — world:" live-path)
  (println "  circle http://localhost:1338/circle/   setup http://localhost:1338/setup/")
  @(promise))
