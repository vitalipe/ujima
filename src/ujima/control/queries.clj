(ns ujima.control.queries
  "Read-side projections over the control plane, plus the one live HW fact they
   need (the current output class). Feed the /api GETs, the POST response
   stitching in the http layer, and the /ui state stream. Reads come from
   settings (intent — what converge drives toward); only :output is live."
  (:require [ujima.control     :as control]
            [ujima.linux.audio :as audio]))


(defn- current-output []
  (audio/output-class (audio/default-sink)))


(defn- next-of
  "The element after `current`, wrapping; (first xs) when current isn't in xs."
  [xs current]
  (let [i (.indexOf (vec xs) current)]
    (if (neg? i)
      (first xs)
      (nth xs (mod (inc i) (count xs))))))


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
  "{:layout <code> :layouts [<code>…] :next <code|nil>} — :next is the cycle
   order published as data, so switcher clients post the concrete layout back
   (idempotent) instead of asking us to advance."
  []
  (let [s       (control/settings)
        layout  (get s [:keyboard :layout])
        layouts (get s [:keyboard :available-layouts])]
    {:layout  layout
     :layouts layouts
     :next    (next-of layouts layout)}))
