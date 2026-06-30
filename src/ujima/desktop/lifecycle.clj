(ns ujima.desktop.lifecycle
  "Per-app launch lifecycle keyed by app-id: :opening -> :running -> :closing. Pure transitions over
   a plain map {app-id {:state kw :since <ms>}}; the atom + side effects live in ujima.desktop. The
   point is to GATE the sync loop — we only pull i3's tree while some app is :opening (its window
   hasn't shown up yet), instead of polling forever.")


(defn open
  "Claim a launch for `id`: mark it :opening as of `now` UNLESS it's already tracked (opening or
   running). Idempotent, so applied with `swap-vals!` it is the launch lock — only the call whose
   swap actually adds the entry (old ≢ new) should spawn; rapid repeat clicks become no-ops, so N
   clicks open at most one instance."
  [m id now]
  (if (contains? m id) m (assoc m id {:state :opening :since now})))

(defn running
  "Its window is tracked now — :running. No-op for an app we aren't tracking the lifecycle of."
  [m id]
  (cond-> m (contains? m id) (assoc-in [id :state] :running)))

(defn closing
  "We asked it to close — :closing until the close is confirmed. No-op if untracked."
  [m id]
  (cond-> m (contains? m id) (assoc-in [id :state] :closing)))

(defn forget
  "Drop the app from the lifecycle (its window closed, or we gave up waiting for it)."
  [m id]
  (dissoc m id))

(defn awaiting?
  "Is any app still :opening — i.e. should the sync loop keep pulling i3's tree?"
  [m]
  (boolean (some (comp #{:opening} :state) (vals m))))

(defn expired
  "App-ids stuck :opening longer than `timeout-ms` as of `now` — their window never appeared."
  [m now timeout-ms]
  (for [[id {:keys [state since]}] m
        :when (and (= :opening state) (> (- now since) timeout-ms))]
    id))
