(ns ujima.tools.cli)

(defn usage []
  (println "Usage: bb tools <stage|update-ujima|jack-in|run>"))


(defn -main [& args]
  (case (first args)
    "stage"        (println "TODO stage "  (rest args))
    "update-ujima" (println "TODO ujima "  (rest args))
    "jack-in"      (println "TODO jack-in" (rest args))
    "run"          (println "TODO run"     (rest args))
    (do
      (usage)
      (System/exit 1))))
