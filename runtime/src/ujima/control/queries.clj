(ns ujima.control.queries
  "Read-side projections over the control plane — pure settings reads, no
   shell-outs: the [:audio :active] setting IS the truth for \"which output\"
   (ujimad's device policy keeps it aligned with the world). Feed the /api
   GETs, the POST response stitching in the http layer, and the /ui stream."
  (:require [ujima.control :as control]))


(defn audio-status
  "{:volume 0-100|nil, :muted bool, :output :usb|:hdmi|nil}. Volume is nil when
   no output is active (widgets grey out)."
  []
  (let [s      (control/settings)
        output (get s [:audio :active])]
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
