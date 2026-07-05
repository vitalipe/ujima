(ns ujima.control.registry
  (:require [lib.util :refer [index-by map-vals]]))


(defn ->registry [{:keys [scopes settings]}]
  (let [in-scope?       (fn [scope {scopes :scopes}] (scope scopes))
        scope->settings (fn [{scope :key}] 
                          (->> settings
                            (filter (partial in-scope? scope))
                            (map :key)
                            (into #{})))]  

    {:scopes         scopes
     :settings       settings 
    
     :settings-by-key   (index-by :key settings)
     :scope-by-key      (index-by :key scopes) 
     :settings-of-scope (->> scopes 
                          (index-by :key) 
                          (map-vals scope->settings))})) 


(defn default-settings [{settings-by-key :settings-by-key}]
  (map-vals :default settings-by-key))


(defn scope->allowed-settings [{settings-of-scope :settings-of-scope} scope]
  (settings-of-scope scope))


(defn scopes [{scopes :scopes}]
  (map :key scopes))


(defn effective-value [_registry scopes-data key]
  ;; Note: use complete scopes, so when we add policy we don't change params
  (->> scopes-data
    (map :settings)
    (map #(get % key)) ;; keys are path vectors, not IFn-able keywords
    (remove nil?)
    (last))) ;; for now we always :merge :override


(defn update-settings-in-scope [registry scope f scope-data]
  (let [allowed (scope->allowed-settings registry scope)]
    (-> scope-data
      (update :settings f)
      (update :settings select-keys allowed))))         
