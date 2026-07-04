(ns ujima.reconcile
  "The OS converge port: drives linux state to match the effective settings.
   Wired into control's :converge-targets by ujima.core — control knows no
   ports. Assemble it with `converge-target`, which proves handler coverage at
   boot instead of warning per pass."
  (:require [ujima.log            :as log]
            [ujima.linux.system   :as system]
            [ujima.linux.keyboard :as keyboard]
            [ujima.linux.audio    :as audio]))


;; Maps each reconcilable setting (from ujima.control.defs) to the ujima.linux
;; operation that reads (:get) and applies (:set) it. All OS logic lives in
;; ujima.linux.*; this namespace only orchestrates. :get nil => no getter, the
;; setting is set-only. An absent output class is normal (unplugged): audio
;; volume(!) no-op to nil, so the setting harmlessly re-applies each pass until
;; the device shows up.
(def handlers
  {[:system :hostname]    {:get system/hostname :set system/hostname!}
   [:system :timezone]    {:get system/timezone :set system/timezone!}
   [:audio :usb :volume]  {:get #(audio/volume :usb)  :set #(audio/volume! :usb %)}
   [:audio :hdmi :volume] {:get #(audio/volume :hdmi) :set #(audio/volume! :hdmi %)}
   [:audio :muted]        {:get audio/mute      :set audio/mute!}
   [:keyboard :layout]    {:get keyboard/layout :set keyboard/layout!}})


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


(defn converge!
  "Drive the OS to match `settings` (the FULL effective map — settings without a
   handler are simply not ours to apply). Idempotent: applies a setting only when
   its current OS value differs. Resilient: a per-setting failure is logged and
   the rest still converge."
  [settings]
  (doseq [[k h] handlers]
    (when (contains? settings k)
      (converge-setting! k (get settings k) h)))
  settings)


(defn converge-target
  "Assemble the OS port for control's :converge-targets. Proves coverage while
   it's at it: every reconcilable setting def must have a handler and every
   handler a def — a mismatch fails the boot loudly, not a runtime pass."
  [setting-defs]
  (let [reconcilable (->> setting-defs
                          (remove #(false? (:reconcile? %)))
                          (map :key)
                          set)
        handled (set (keys handlers))
        missing (vec (remove handled reconcilable))
        orphans (vec (remove reconcilable handled))]
    (when (or (seq missing) (seq orphans))
      (throw (ex-info "reconcile: handlers do not match setting defs"
                      {:missing-handlers missing :orphan-handlers orphans})))
    converge!))
