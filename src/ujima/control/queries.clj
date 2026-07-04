(ns ujima.control.queries
  "Read-side projections over the control plane, plus the one live HW fact they
   need (the current output class). Feed the /api GETs, the POST response
   stitching in the http layer, and the /ui state stream. Reads come from
   settings (intent — what converge drives toward); only :output is live."
  (:require [ujima.control     :as control]
            [ujima.linux.audio :as audio]))


(defn current-output
  "The one live HW fact the projections need: the output class of the default
   sink (:usb | :hdmi | nil)."
  []
  (audio/output-class (audio/default-sink)))


(defn audio-status
  "{:volume 0-100|nil, :muted bool, :output :usb|:hdmi|nil}. Volume is nil when
   no output classifies (widgets grey out)."
  []
  (let [s      (control/settings)
        output (current-output)]
    {:volume (when output (get s [:audio output :volume]))
     :muted  (get s [:audio :muted])
     :output output}))


(defn keyboard-status
  "{:layout <code> :layouts [<code>…]} — the domain facts only; presentation
   derivations (the switcher's cycle order) live in the UI projection."
  []
  (let [s (control/settings)]
    {:layout  (get s [:keyboard :layout])
     :layouts (get s [:keyboard :available-layouts])}))
