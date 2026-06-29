(ns ujima.desktop.launch
  "Per-:kind launch strategy: turn a catalog app into the argv to spawn. The launch mechanics
   (--class stamping, shared ephemeral profile, cache caps) live here, NOT in each catalog entry,
   so a catalog entry stays one declarative line. Pure argv construction — the actual spawn and
   the i3 window::new correlation live in ujima.desktop.windows.")


(defn window-class
  "The WM_CLASS the agent assigns/tracks this app's windows by: :web apps are stamped with a
   per-app class at launch; other kinds carry their natural :wm-class."
  [app]
  (or (:wm-class app)
      (str "ujima-" (name (:id app)))))


(defn web-argv
  "Chromium --app argv: no chrome, a per-app WM_CLASS, and a SHARED ephemeral profile in tmpfs
   Per-app (not shared): chromium's --class is per-process, so a shared profile merges apps into
   one (the 2nd --app window inherits the 1st's class). A profile per app keeps them separate."
  [app {:keys [chromium profile-dir]}]
  [(or chromium "chromium")
   (str "--app=" (:url app))
   (str "--class=" (window-class app))
   (str "--user-data-dir=" profile-dir "/" (name (:id app)))
   "--disk-cache-size=1"
   "--no-first-run"
   "--no-default-browser-check"])


(defn desktop-argv
  "Native desktop app: the catalog :exec verbatim (tracked by its natural :wm-class; we can't
   reliably stamp --class on arbitrary apps)."
  [app _ctx]
  (vec (:exec app)))


(defn launch-argv
  "Build the spawn argv for `app` given `ctx` ({:chromium :profile-dir}). Throws for kinds that
   aren't process-launched — :shell is the launcher, an eww window."
  [app ctx]
  (case (:kind app)
    :web     (web-argv app ctx)
    :desktop (desktop-argv app ctx)
    (throw (ex-info "app kind is not process-launched" {:id (:id app) :kind (:kind app)}))))
