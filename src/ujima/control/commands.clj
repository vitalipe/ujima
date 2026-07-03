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


(defn keyboard-status []
  (let [s (control/settings)]
    {:layout  (get s [:keyboard :layout])
     :layouts (get s [:keyboard :available-layouts])}))


(defn set-volume!
  "Set the current output class's volume. Clamps BEFORE storing — an out-of-range
   stored value would re-apply on every reconcile pass (HW caps at 100)."
  [value]
  (when-not (number? value)
    (throw (ex-info "volume must be a number" {:error :request/malformed :value value})))
  (if-let [output (current-output)]
    (let [v (-> value int (max 0) (min 100))]
      (control/settings! :session [:audio output :volume] v)
      {:volume v})
    (throw (ex-info "no classifiable audio output" {:error :audio/no-output}))))


(defn set-mute! [muted]
  (when-not (boolean? muted)
    (throw (ex-info "muted must be a boolean" {:error :request/malformed :value muted})))
  (control/settings! :session [:audio :muted] muted)
  {:muted muted})


(defn- next-of
  "The element after `current`, wrapping; (first xs) when current isn't in xs."
  [xs current]
  (let [i (.indexOf (vec xs) current)]
    (if (neg? i)
      (first xs)
      (nth xs (mod (inc i) (count xs))))))


(defn next-layout!
  "Cycle to the next available layout. Read-then-write: two simultaneous clicks may
   land on the same next layout — harmless for a tray button."
  []
  (let [s       (control/settings)
        layouts (get s [:keyboard :available-layouts])]
    (when (empty? layouts)
      (throw (ex-info "no available layouts" {:error :keyboard/no-layouts})))
    (let [nxt (next-of layouts (get s [:keyboard :layout]))]
      (control/settings! :session [:keyboard :layout] nxt)
      {:layout nxt})))
