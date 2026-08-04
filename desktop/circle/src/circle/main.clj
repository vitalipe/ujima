(ns circle.main
  "Dev entry: the Circle panel over the file-backed mock fleet."
  (:require [circle.mock :as mock]
            [circle.http :as http]))


(def ^:private seed-path "desktop/circle/dev/world.edn")
(def ^:private live-path "tmp/circle/world.edn")
(def ^:private ui-root   "desktop/circle/ui")


(defn -main [& _]
  (mock/seed! seed-path live-path)
  (http/init! {:ui-root ui-root :transport (mock/transport live-path)})
  (println "circle: mock fleet at http://localhost:1338 — world:" live-path)
  @(promise))
