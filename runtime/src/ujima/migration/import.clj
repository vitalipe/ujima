(ns ujima.migration.import
  "Apply a command file through control's writer. Validates every entry first; any error
   applies nothing.

   An upgrade runs the NEW slot's copy of this, so this side decides what it accepts: a
   setting the new version renamed or dropped is refused here, by name."
  (:require [malli.core  :as m]
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
   empty :errors = the whole file is applicable.

   Pure — no control/init!, so an upgrade can ask a freshly written slot what it would
   refuse without standing that slot's control plane up."
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
