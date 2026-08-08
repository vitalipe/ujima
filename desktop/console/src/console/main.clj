(ns console.main
  "Dev entry: the console (Circle + Setup panels) over the file-backed mock
   fleet."
  (:require [console.mock :as mock]
            [console.http :as http]))


(def ^:private seed-path "desktop/console/dev/world.edn")
(def ^:private live-path "tmp/console/world.edn")

(def ^:private ui-roots
  {:console "desktop/console/ui"
   :circle  "desktop/circle/ui"
   :setup   "desktop/setup/ui"})


(defn -main [& _]
  (mock/seed! seed-path live-path)
  (http/init! {:ui-roots ui-roots :transport (mock/transport live-path)})
  (println "console: mock fleet at http://localhost:1338 — world:" live-path)
  (println "  circle http://localhost:1338/circle/   setup http://localhost:1338/setup/")
  @(promise))
