(ns tools.circle.sim
  "A fake circle on the LAN: N machines that answer /api the way ujimad does, so the console
   can be driven against a classroom instead of one Pi. Never installed on an image.

   One listener serves the whole fleet — http-kit reports the address the client dialled, so
   the dispatcher picks the machine by it. Each machine gets its own lib.http/app, so keys,
   gates and signatures are per machine even though there is one socket.

   Trees, params, gates and 404s come from ujimad's own code; only the data is fake."
  (:require [clojure.edn      :as edn]
            [clojure.string   :as str]
            [babashka.fs      :as fs]
            [org.httpkit.server :as http]
            [lib.http         :as lib-http]
            [ujima.api.auth   :as auth]
            [ujima.api.routes :as routes]
            [ujima.linux.sudo :refer [sudo! sudo?]]
            [schema.ujima.api.commands :as api]))


(def ^:private port         1337)
(def default-token  "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
(def ^:private default-pool "tools/config/pool.edn")
(def ^:private state-file   (str (fs/path (or (System/getenv "XDG_RUNTIME_DIR") "/tmp")
                                          "ujima-fake-circle.edn")))
(def ^:private reboot-ms    15000)

(defonce ^:private fleet* (atom {}))   ;; address -> machine


;; ── the LAN ─────────────────────────────────────────────────────────────────

(defn- lan
  "The interface the default route leaves by, and the prefix it carries."
  []
  (let [route (:out (sudo? :ip :route :get "1.1.1.1"))
        iface (second (re-find #"dev (\S+)" (str route)))
        addr  (:out (sudo? :ip "-4" "-br" :addr :show iface))]
    {:iface  iface
     :prefix (or (second (re-find #"/(\d+)" (str addr))) "24")}))

(defn- hosts
  "\"192.168.1.200-229\" -> the addresses it names."
  [range-str]
  (let [[_ a b c from to] (re-find #"^(\d+)\.(\d+)\.(\d+)\.(\d+)-(\d+)$" (str range-str))]
    (when-not to
      (throw (ex-info (str "range must look like 192.168.1.200-229, got: " range-str)
                      {:range range-str})))
    (mapv #(str a "." b "." c "." %) (range (parse-long from) (inc (parse-long to))))))

;; arping exit 0 = something answered. NB this is Habets' arping, where -D means
;; "print dots", NOT duplicate address detection — the plain exit code is the check.
(defn- taken? [iface ip]
  (:ok? (sudo? :arping "-c" "2" "-w" "2" "-I" iface ip)))

(defn- claim! [iface prefix ip]
  (sudo! :ip :addr :add (str ip "/" prefix) :dev iface))

(defn- release! [iface prefix ip]
  (sudo? :ip :addr :del (str ip "/" prefix) :dev iface))


;; ── what we hold, so a crash can be cleaned up ──────────────────────────────

(defn- alive? [pid]
  (and pid (fs/exists? (str "/proc/" pid))))

(defn- read-state []
  (when (fs/exists? state-file)
    (try (edn/read-string (slurp state-file)) (catch Exception _ nil))))

(defn- write-state! [state]
  (spit state-file (pr-str state)))

(defn- release-all! [{:keys [iface prefix addresses]}]
  (doseq [ip addresses] (release! iface prefix ip))
  (fs/delete-if-exists state-file)
  (count addresses))


;; ── the fleet ───────────────────────────────────────────────────────────────

(def ^:private hex (java.util.HexFormat/of))

(defn- digest [& parts]
  (.formatHex hex (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           (.getBytes (str/join "/" parts) "UTF-8"))))

(defn- id-of [seed name i]
  (let [h (digest "id" seed name i)]
    (str (subs h 0 8) "-" (subs h 8 12) "-" (subs h 12 16) "-" (subs h 16 20) "-" (subs h 20 32))))

(defn- catalog
  "The image's own app dirs — the same source the real catalog is scanned from."
  []
  (->> (fs/list-dir "os/apps")
       (keep (fn [dir]
               (let [id  (fs/file-name dir)
                     app (try (edn/read-string (slurp (str (fs/path dir "app.edn")))) (catch Exception _ nil))]
                 (when app
                   {:id       (keyword id)
                    :label    (:label app)
                    :icon     (str "/ujima/apps/" id "/icon.svg")
                    :category (:category app)
                    :hidden   (boolean (:hidden app))}))))
       (sort-by :id)
       vec))

(defn- machine
  "One fake, seeded: everything the pool file does not name is derived from its name."
  [{:keys [seed catalog now]} i addr {:keys [name app muted off]}]
  (let [h (digest "fact" seed name)]
    {:addr     addr
     :id       (id-of seed name i)
     :serial   (subs h 0 16)
     :off?     (boolean off)
     :booted   (- now (* 60000 (+ 3 (mod (Integer/parseInt (subs h 0 4) 16) 400))))
     :offset   0                                   ;; wall-clock offset, moved by system/clock
     :running  (when app (some #(when (= app (:id %)) (assoc % :title (str (:label %) " — UjimaOS")
                                                               :fullscreen false))
                               catalog))
     :settings {:device   {[:system :hostname]           name
                           [:system :timezone]           "Africa/Dar_es_Salaam"
                           [:keyboard :available-layouts] ["us" "tz"]
                           [:keyboard :layout]           "us"
                           [:audio :active]              :hdmi
                           [:audio :hdmi :volume]        40}
                :session  (cond-> {} muted (assoc [:audio :muted] true))
                :activity {}}}))

(def ^:private scope-order [:device :session :activity])

(defn- effective
  "The last scope that HOLDS the path wins — `contains?`, not a nil check, so a
   setting held at false is still held."
  [m path]
  (let [held (filter #(contains? (get-in m [:settings %] {}) path) scope-order)]
    (when-let [scope (last held)] (get-in m [:settings scope path]))))


;; ── one machine's answers ───────────────────────────────────────────────────

(defn- clock-ms [m] (+ (System/currentTimeMillis) (:offset m)))

(defn- nodes [addr apps]
  (let [m #(get @fleet* addr)]
    {"schema"   (constantly 1)
     "id"       #(:id (m))
     "device"   #(hash-map :serial (:serial (m)) :model "Raspberry Pi 500 Rev 1.0")
     "image"    (constantly {:version "v0.4.0-fake"})
     "disk"     (constantly {:type "ab" :slot "a"
                             :storage  {:total-mb 5889 :free-mb 5542}
                             :settings {:total-mb 973  :free-mb 906}})
     "apps"     (constantly apps)

     "desktop/locked"  (constantly false)
     "desktop/running" #(:running (m))
     "desktop/catalog" (constantly apps)

     "audio"    #(let [it (m)
                       out (effective it [:audio :active])]
                   {:volume (effective it [:audio out :volume])
                    :muted  (boolean (effective it [:audio :muted]))
                    :output out})

     "keyboard" #(let [it (m)]
                   {:layout            (effective it [:keyboard :layout])
                    :available-layouts (effective it [:keyboard :available-layouts])})

     "net"      (constantly {:ip addr
                             :interfaces {:wlan0 {:up true :ip addr :prefix 24
                                                  :mac "2c:cf:67:00:00:00" :gateway nil :dhcp true}}})

     "system/hostname" #(effective (m) [:system :hostname])
     "system/timezone" #(effective (m) [:system :timezone])
     "system/clock-ms" #(clock-ms (m))

     "monitor/uptime-minutes" #(quot (- (System/currentTimeMillis) (:booted (m))) 60000)
     "monitor/messages"       (constantly [])}))


(defn- update-machine! [addr f & args]
  (apply swap! fleet* update addr f args))

(defn- put! [addr scope path value]
  (update-machine! addr assoc-in [:settings scope path] value))

(defn- power! [addr off?]
  (update-machine! addr assoc :off? off?))

(defn- reboot! [addr]
  (power! addr true)
  (future
    (Thread/sleep reboot-ms)
    (update-machine! addr #(-> % (assoc :off? false :booted (System/currentTimeMillis) :running nil)
                                 (assoc-in [:settings :session] {})
                                 (assoc-in [:settings :activity] {})))))

(defn- app-entry [apps id]
  (some #(when (= (keyword id) (:id %))
           (assoc % :title (str (:label %) " — UjimaOS") :fullscreen false))
        apps))

(defn- handlers
  "One per verb the contract declares — the zip below fails if that stops being true."
  [addr apps]
  {"app/open"     (fn [{:keys [app]}] (update-machine! addr assoc :running (app-entry apps app)))
   "app/switch"   (fn [{:keys [app]}] (update-machine! addr assoc :running (app-entry apps app)))
   "app/close"    (fn [_] (update-machine! addr assoc :running nil))
   "app/home"     (fn [_] (update-machine! addr assoc :running nil))
   "app/open-url" (fn [{:keys [url]}]
                    (update-machine! addr assoc :running
                                     {:id :web :label (or (second (re-find #"^(?:\w+://)?([^/:?#]+)" url)) url)
                                      :category :explore :title url :fullscreen false}))

   "audio/volume" (fn [{:keys [scope value]}]
                    (let [out (effective (get @fleet* addr) [:audio :active])]
                      (put! addr scope [:audio out :volume] (-> value long (max 0) (min 100)))))

   "keyboard/layout" (fn [{:keys [scope layout]}] (put! addr scope [:keyboard :layout] layout))

   "settings/**"  (fn [{:keys [path value scope]}] (put! addr scope path value))

   "clear/:scope/**" (fn [{:keys [scope path]}]
                       (if (seq path)
                         (update-machine! addr update-in [:settings scope] dissoc path)
                         (update-machine! addr assoc-in [:settings scope] {})))

   "system/clock" (fn [{:keys [epoch timezone]}]
                    (when timezone (put! addr :device [:system :timezone] timezone))
                    (update-machine! addr assoc :offset (- epoch (System/currentTimeMillis))))

   "system/restart"  (fn [_] (reboot! addr))
   "system/poweroff" (fn [_] (power! addr true))})

(defn- with-handlers [specs hs]
  (assert (= (set (keys specs)) (set (keys hs)))
          (str "the fake is out of step with the contract — spec with no handler: "
               (sort (remove hs (keys specs)))
               ", handler with no spec: " (sort (remove specs (keys hs)))))
  (into {} (for [[path spec] specs] [path (assoc spec :handler (hs path))])))

(defn- ->app
  "This machine's whole HTTP surface: ujimad's routes, ujimad's gate, its own key."
  [addr apps key]
  (let [cfg  {:key key :self-id (:id (get @fleet* addr)) :window-ms 60000}
        gate (auth/->gate cfg)]
    (lib-http/app
      {:log  (fn [& _])
       :sign (auth/->sign cfg)
       :endpoints
       {"api" {:errors api/errors
               :routes (merge (gate (routes/commands {:base "commands"
                                                      :commands (with-handlers api/commands (handlers addr apps))}))
                              (routes/queries {:base "query/machine" :nodes (nodes addr apps)}))}}})))


;; ── the one listener ────────────────────────────────────────────────────────

(defn- dispatch [req]
  (let [m (get @fleet* (:server-name req))]
    (cond
      ;; the listener also owns this host's own address; an unsigned 404 there reads to the
      ;; console as a machine from another circle, so say nothing at all
      (nil? m)  (http/as-channel req {:on-open http/close})
      ;; a machine that is off accepts the connection and never answers — a refusal would
      ;; be a lie, since a dead Pi does not refuse either
      (:off? m) (http/as-channel req {})
      :else     ((:app m) req))))


;; ── the verbs ───────────────────────────────────────────────────────────────

(defn up!
  [{:keys [range token seed pool skip-occupied]
    :or   {token default-token seed "1" pool default-pool}}]
  (when-let [{:keys [pid] :as held} (read-state)]
    (if (alive? pid)
      (throw (ex-info (str "a fake circle is already running (pid " pid ") holding "
                           (count (:addresses held)) " addresses — stop it, or `bb fake-circle clean`")
                      {:pid pid}))
      (do (println "clearing a stale claim from pid" pid)
          (release-all! held))))

  (let [{:keys [iface prefix]} (lan)
        wanted   (hosts range)
        roster   (:machines (edn/read-string (slurp pool)))
        _        (when (> (count roster) (count wanted))
                   (throw (ex-info (str (count roster) " machines in " pool " but the range holds "
                                        (count wanted)) {})))
        _        (println (str "probing " (count wanted) " addresses on " iface "..."))
        occupied (vec (filter (partial taken? iface) wanted))
        free     (remove (set occupied) wanted)]

    (when (seq occupied)
      (if skip-occupied
        (println "skipping occupied:" (str/join " " occupied))
        (throw (ex-info (str "occupied, claiming nothing: " (str/join " " occupied)
                             "\n  (--skip-occupied to take the rest)")
                        {:occupied occupied}))))
    (when (< (count free) (count roster))
      (throw (ex-info (str "only " (count free) " free addresses for " (count roster) " machines") {})))

    (let [apps  (catalog)
          now   (System/currentTimeMillis)
          taken (vec (take (count roster) free))]
      (write-state! {:pid (.pid (java.lang.ProcessHandle/current))
                     :iface iface :prefix prefix :addresses taken})
      (doseq [ip taken] (claim! iface prefix ip))

      (reset! fleet*
              (into {} (map-indexed (fn [i [ip entry]]
                                      [ip (machine {:seed seed :catalog apps :now now} i ip entry)])
                                    (map vector taken roster))))
      ;; the app closes over the machine's id, so it is built after the fleet exists
      (doseq [ip taken] (update-machine! ip assoc :app (->app ip apps token)))

      (http/run-server dispatch {:ip "0.0.0.0" :port port})
      (println (str "fake circle up: " (count taken) " machines on " (first taken) "-"
                    (last (str/split (last taken) #"\.")) ", token " (subs token 0 8) "…"))
      (doseq [[ip m] (sort-by first @fleet*)]
        (println (format "  %-15s %-10s %s%s" ip (effective m [:system :hostname])
                         (or (some-> m :running :label) "—") (if (:off? m) "  (off)" ""))))
      (println "ctrl-c to release the addresses")
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. #(some-> (read-state) release-all!)))
      @(promise))))


(defn cleanup!
  [_]
  (if-let [{:keys [pid] :as held} (read-state)]
    (if (alive? pid)
      (println (str "pid " pid " is still running and holds " (count (:addresses held))
                    " addresses — stop it first"))
      (println "released" (release-all! held) "addresses"))
    (println "nothing claimed")))
