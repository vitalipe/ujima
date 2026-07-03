(ns ujima.control.commands
  "User-intent verbs over the control plane — the one place that maps screen-facing
   intent (\"set THE volume\") onto concrete settings (which output class's volume).
   Verbs are synchronous: control's lock already serializes writers, and callers
   arrive at click rate (drag floods are debounced at the widget edge before they
   get here). Writes land in the :session scope — cleared each session; :device
   persistence arrives with the console. Failures throw ex-info {:error <kw>} for
   the HTTP tier to map onto statuses."
  (:require [ujima.control     :as control]
            [ujima.linux.audio :as audio]))


(defn- current-output []
  (audio/output-class (audio/default-sink)))


(defn audio-status
  "{:volume 0-100|nil, :muted bool, :output :usb|:hdmi|nil}. Volume/muted are the
   effective SETTINGS (intent — what reconcile drives toward); only :output is read
   from live sink state. Volume is nil when no output classifies."
  []
  (let [s      (control/settings)
        output (current-output)]
    {:volume (when output (get s [:audio output :volume]))
     :muted  (get s [:audio :muted])
     :output output}))


(defn- next-of
  "The element after `current`, wrapping; (first xs) when current isn't in xs."
  [xs current]
  (let [i (.indexOf (vec xs) current)]
    (if (neg? i)
      (first xs)
      (nth xs (mod (inc i) (count xs))))))


(defn keyboard-status
  "{:layout <code> :layouts [<code>…] :next <code|nil>} — :next is the cycle
   order published as data, so a switcher widget posts the concrete layout back
   (idempotent) instead of asking us to advance."
  []
  (let [s       (control/settings)
        layout  (get s [:keyboard :layout])
        layouts (get s [:keyboard :available-layouts])]
    {:layout  layout
     :layouts layouts
     :next    (next-of layouts layout)}))


(defn set-volume!
  "Set the current output class's volume; returns the fresh audio resource.
   Clamps BEFORE storing — an out-of-range stored value would re-apply on every
   reconcile pass (HW caps at 100)."
  [value]
  (when-not (number? value)
    (throw (ex-info "volume must be a number" {:error :request/malformed :value value})))
  (if-let [output (current-output)]
    (let [v (-> value int (max 0) (min 100))]
      (control/settings! :session [:audio output :volume] v)
      (audio-status))
    (throw (ex-info "no classifiable audio output" {:error :audio/no-output}))))


(defn set-mute!
  "Set mute to a concrete desired state (idempotent); returns the fresh audio
   resource."
  [muted]
  (when-not (boolean? muted)
    (throw (ex-info "muted must be a boolean" {:error :request/malformed :value muted})))
  (control/settings! :session [:audio :muted] muted)
  (audio-status))


(defn set-layout!
  "Set a concrete layout code; returns the fresh keyboard resource. Only codes
   in available-layouts are accepted — a stray code persisted into a scope would
   fail reconcile on every pass, so it must be rejected loudly here at the edge."
  [code]
  (when-not (string? code)
    (throw (ex-info "layout must be a string" {:error :request/malformed :value code})))
  (let [layouts (get (control/settings) [:keyboard :available-layouts])]
    (when-not (some #{code} layouts)
      (throw (ex-info "layout not in available-layouts"
                      {:error :keyboard/unknown-layout :value code :layouts layouts})))
    (control/settings! :session [:keyboard :layout] code)
    (keyboard-status)))
