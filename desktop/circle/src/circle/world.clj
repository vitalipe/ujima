(ns circle.world
  "Dev TUI that drives the mock world file: add/drop random machines, jump to
   set counts. Edits tmp/circle/world.edn in place — the running mock server
   re-reads it every poll, so changes land in the panel within ~2s. Separate
   process from the server: last writer wins, same contract as hand-editing."
  (:require [clojure.edn      :as edn]
            [clojure.pprint   :as pprint]
            [clojure.string   :as str]
            [babashka.process :as process]
            [circle.mock      :as mock]))


(def ^:private seed-path "desktop/circle/dev/world.edn")
(def ^:private live-path "tmp/circle/world.edn")

(def ^:private name-pool
  ["Kobe" "Ndege" "Samaki" "Nyoka" "Kanga" "Kima" "Fisi" "Mbuni" "Kiboko"
   "Sungura" "Paka" "Mbwa" "Korongo" "Tai" "Popo" "Panya" "Kuku" "Bata"
   "Njiwa" "Kasuku" "Kunguru" "Mjusi" "Chura" "Nge" "Buibui" "Nzige"
   "Kipepeo" "Siafu" "Nyuki" "Konokono" "Kaa" "Pweza" "Nyangumi" "Papa"
   "Pomboo" "Kanu" "Kicheche" "Kindi" "Kwale" "Kakakuona"])


(defn- read-world  []      (edn/read-string (slurp live-path)))
(defn- write-world! [world] (spit live-path (with-out-str (pprint/pprint world))))


(defn- gen-machine [world]
  (let [used    (set (map :name (:peers world)))
        name    (or (first (shuffle (remove used name-pool)))
                    (str "Rafiki" (inc (count (:peers world)))))
        online? (< (rand) 0.9)
        app     (when (and online? (< (rand) 0.7))
                  (rand-nth (:catalog world)))
        peer    {:id      (str "pi-" (str/lower-case name))
                 :name    name
                 :online  online?
                 :desktop {:locked (boolean (and online? (< (rand) 0.125)))}
                 :apps    {:running app}
                 :audio   {:muted (< (rand) 0.125) :out :hdmi :volume 40}}
        r       (rand)
        knob    (cond (< r 0.10) {:reply :noreply}
                      (< r 0.22) {:delay-ms (+ 2000 (rand-int 3000))})]
    (cond-> (update world :peers conj peer)
      knob (update :knobs assoc (:id peer) knob))))

(defn- drop-machine
  "Removes the last non-self peer (generated first, seed last, never self)."
  [world]
  (let [idx (->> (:peers world)
                 (keep-indexed (fn [i p] (when (not= (:id p) (:self world)) i)))
                 last)]
    (if-not idx
      world
      (let [id (:id (nth (:peers world) idx))]
        (-> world
            (update :peers #(vec (concat (subvec % 0 idx) (subvec % (inc idx)))))
            (update :knobs dissoc id))))))

(defn- set-count [world n]
  (let [n (max 1 (min 99 n))]
    (loop [world world]
      (let [cur (count (:peers world))]
        (cond
          (< cur n) (recur (gen-machine world))
          (> cur n) (let [next (drop-machine world)]
                      (if (= next world) world (recur next)))
          :otherwise world)))))


(defn- marker [world p]
  (let [knob (get-in world [:knobs (:id p)])]
    (str (:name p)
         (when (= (:id p) (:self world))       "*")
         (when-not (:online p)                 "·off")
         (when (get-in p [:desktop :locked])   "·l")
         (when (get-in p [:audio :muted])      "·m")
         (cond (= :noreply (:reply knob)) "·nr"
               (:delay-ms knob)           "·slow"))))

(defn- render [world]
  (print "\u001b[2J\u001b[H")
  (println (str "circle world — " (count (:peers world)) " machines   (" live-path ")"))
  (println (str/join "  " (map (partial marker world) (:peers world))))
  (println "   * self  ·off offline  ·l locked  ·m muted  ·nr no-reply  ·slow slow")
  (println)
  (println "[a]dd  [d]rop  [0]→6  [1]→10  [2]→24  [3]→32  [n] custom  [r]eseed  [q]uit")
  (flush))


;; best-effort: fails harmlessly when stdin is a pipe (scripted runs)
(defn- stty! [& args]
  (try (apply process/shell "stty" args) (catch Exception _ nil)))

(defn- ask-number []
  (stty! "icanon" "echo")
  (print "count: ") (flush)
  (let [n (parse-long (str/trim (or (read-line) "")))]
    (stty! "-icanon" "-echo")
    n))

(defn -main [& _]
  (mock/seed! seed-path live-path)
  (stty! "-icanon" "-echo")
  (try
    (loop []
      (render (read-world))
      (let [byte (.read System/in)
            key  (when-not (neg? byte) (char byte))]   ;; EOF quits
        (case key
          nil nil
          \a (do (write-world! (gen-machine (read-world)))  (recur))
          \d (do (write-world! (drop-machine (read-world))) (recur))
          \0 (do (write-world! (set-count (read-world) 6))  (recur))
          \1 (do (write-world! (set-count (read-world) 10)) (recur))
          \2 (do (write-world! (set-count (read-world) 24)) (recur))
          \3 (do (write-world! (set-count (read-world) 32)) (recur))
          \n (do (when-let [n (ask-number)]
                   (write-world! (set-count (read-world) n)))
                 (recur))
          \r (do (spit live-path (slurp seed-path)) (recur))
          \q nil
          (recur))))
    (finally
      (stty! "icanon" "echo")
      (println))))
