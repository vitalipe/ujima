(ns ujima.control.reconcile
  (:require [ujima.log           :as log]
            [ujima.linux.system  :as system]
            [ujima.linux.desktop :as desktop]))


;; Per-class volume glue: getter is nil while no sink of that class is present, so the
;; setter (a no-op then) re-applies each pass until the device shows up.
(defn- class-volume [class]
  (some->> (desktop/sink-for-class class) :id desktop/volume))

(defn- class-volume! [class value]
  (when-let [sink (desktop/sink-for-class class)]
    (desktop/volume! (:id sink) value)))


;; Maps each setting (from ujima.control.defs) to the ujima.linux operation that
;; reads (:get) and applies (:set) it. All OS logic lives in ujima.linux.*; this
;; namespace only orchestrates. :get nil => no getter, the setting is set-only.
(def handlers
  {[:system :hostname]    {:get system/hostname :set system/hostname!}
   [:system :timezone]    {:get system/timezone :set system/timezone!}
   [:audio :usb :volume]  {:get #(class-volume :usb)  :set #(class-volume! :usb %)}
   [:audio :hdmi :volume] {:get #(class-volume :hdmi) :set #(class-volume! :hdmi %)}
   [:audio :muted]        {:get desktop/mute    :set desktop/mute!}
   [:keyboard :layout]    {:get nil             :set #(system/keyboard-layouts! [%])}}) ;; getter is a TODO


(defn- converge-setting! [k desired {getter :get setter :set}]
  (try
    (let [current (when getter (getter))]
      (if (and getter (= current desired))
        (log/debug "reconcile: in sync" k)
        (do
          (log/info "reconcile: applying" {:setting k :value desired})
          (setter desired))))
    (catch Throwable e
      (log/error "reconcile: failed" {:setting k :error (ex-message e)}))))


(defn reconcile!
  "Drive the OS to match `settings` (the merged effective settings). Idempotent:
   applies a setting only when its current OS value differs. Resilient: a per-setting
   failure is logged and the rest still converge."
  [settings]
  (doseq [[k desired] settings]
    (if-let [h (handlers k)]
      (converge-setting! k desired h)
      (log/warn "reconcile: no handler for setting" k)))
  settings)
