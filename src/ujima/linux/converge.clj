(ns ujima.linux.converge
  "\"Here is ujima state — go apply it to linux.\" The OS converge port, wired
   into control's :converge-targets by ujima.core. Applies what it has handlers
   for — a setting nobody handles is simply not linux's business. prv gates the
   work: a write-converge touches only the keys that changed; a nil prv (boot,
   udev — assume nothing) sweeps everything through the getter-compare."
  (:require [ujima.log            :as log]
            [ujima.linux.system   :as system]
            [ujima.linux.keyboard :as keyboard]
            [ujima.linux.audio    :as audio]))


;; Maps a setting (ujima.control.defs key) to the ujima.linux operation that
;; reads (:get) and applies (:set) it. All OS logic lives in ujima.linux.*; this
;; namespace only orchestrates. :get nil => no getter, the setting is set-only.
;; An absent output class is normal (unplugged): audio volume(!) no-op to nil,
;; so the setting harmlessly re-applies each pass until the device shows up.
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
        (log/debug "converge: in sync" k)
        (do
          (log/info "converge: applying" {:setting k :value desired})
          (setter desired))))
    (catch Throwable e
      (log/error "converge: failed" {:setting k :error (ex-message e)}))))


(defn converge!
  "Drive linux to match `settings` (the full effective map). Keys equal in `prv`
   are skipped wholesale; nil prv converges everything. Idempotent (getter-compare
   per setting) and resilient (a per-setting failure is logged, the rest still
   converge)."
  [settings prv]
  (doseq [[k h] handlers]
    (when (and (contains? settings k)
               (or (nil? prv)
                   (not= (get settings k) (get prv k))))
      (converge-setting! k (get settings k) h)))
  settings)
