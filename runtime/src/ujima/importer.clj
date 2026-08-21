(ns ujima.importer
  "Apply a settings command file — a vector of {:scope :setting :value} — through
   control's writer. Validates EVERY entry first; any error applies nothing.
   The installer chroots into a freshly written slot and runs this to seed it;
   the same tool works on a live machine."
  (:require [lib.io :as io]
            [malli.core  :as m]
            [malli.error :as me]
            [schema.ujima.settings :as defs]
            [ujima.control :as control]))


(def ^:private specs
  (into {} (map (juxt :key #(select-keys % [:shape :scopes]))) defs/settings))

(def ^:private known-scopes
  (into #{} (map :key) defs/scopes))

(def ^:private ephemeral-scopes
  (into #{} (comp (remove :persist?) (map :key)) defs/scopes))


(defn- shape-error [shape value]
  (->> (me/humanize (m/explain shape value)) flatten (remove nil?) first))


(defn- entry-error
  "nil when ENTRY is a valid command, else a human line."
  [{:keys [scope setting value] :as entry}]
  (let [{:keys [shape scopes]} (get specs setting)]
    (cond
      (not (map? entry))             "entry is not a map"
      (not (known-scopes scope))     (str "unknown scope " (pr-str scope))
      (not shape)                    (str "unknown setting " (pr-str setting))
      (not (contains? scopes scope)) (str (pr-str setting) " cannot be set in scope "
                                          (pr-str scope) " (allowed: " (pr-str scopes) ")")
      (not (m/validate shape value)) (shape-error shape value)
      :valid                         nil)))


(defn validate
  "-> {:errors [{:entry :error}] :warnings [{:entry :warning}]};
   empty :errors = the whole file is applicable."
  [entries]
  (if-not (and (sequential? entries) (seq entries))
    {:errors   [{:error "command file must be a non-empty vector of {:scope :setting :value} entries"}]
     :warnings []}
    {:errors   (vec (keep (fn [entry]
                            (when-let [err (entry-error entry)]
                              {:entry entry :error err}))
                          entries))
     :warnings (vec (keep (fn [{:keys [scope] :as entry}]
                            (when (ephemeral-scopes scope)
                              {:entry entry :warning (str (pr-str scope) " does not persist — gone at next boot")}))
                          entries))}))


(defn- apply!
  "One control write per scope; entries are pre-validated."
  [entries]
  (doseq [[scope group] (group-by :scope entries)]
    (control/update-settings! scope
      #(merge % (into {} (map (juxt :setting :value)) group)))))


(defn import!
  "Validate then apply (all-or-nothing). control/init! must have run.
   -> {:ok? bool :errors [..] :warnings [..] :applied n}"
  [entries {:keys [validate-only]}]
  (let [{:keys [errors warnings]} (validate entries)]
    (cond
      (seq errors)  {:ok? false :errors errors :warnings warnings :applied 0}
      validate-only {:ok? true  :errors []     :warnings warnings :applied 0}
      :apply        (do (apply! entries)
                        {:ok? true :errors [] :warnings warnings :applied (count entries)}))))


(defn -main [& args]
  (let [validate-only? (boolean (some #{"--validate-only"} args))
        [file & extra] (remove #{"--validate-only"} args)]

    (when (or (nil? file) (seq extra))
      (println "usage: bb -m ujima.importer [--validate-only] <commands.edn>")
      (System/exit 2))

    (let [entries (io/slurp-edn file ::unreadable)]
      (when (= ::unreadable entries)
        (println "cannot read" file)
        (System/exit 2))

      (let [cfg (io/slurp-config "config" "ujimad")]
        (control/init! {:storage          (get-in cfg [:control :storage])
                        :tmp              (get-in cfg [:control :tmp])
                        :converge-targets []}))

      (let [{:keys [ok? errors warnings applied]} (import! entries {:validate-only validate-only?})]
        (doseq [{:keys [entry warning]} warnings]
          (println "WARN: " warning "—" (pr-str entry)))
        (doseq [{:keys [entry error]} errors]
          (println "ERROR:" error "—" (pr-str entry)))
        (if ok?
          (println (if validate-only? "valid" (str "applied " applied " settings")))
          (do (println "nothing applied")
              (System/exit 1)))))))
