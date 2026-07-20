(ns tools.scripts.appcatalog
  "Single source of truth for the launcher apps. Each entry pairs the catalog SPEC the eww
   launcher/dock render + launch with its build-time install recipe (:apt packages or a
   :fetch payload). Packages are never vendored in git — they install like apt at build
   time; only the spec lives here. Adding an app is one entry.

   Three build seams share this one data source:
     install!         apt-installs every :apt (deduped) + fetches each :fetch. Called from
                      tools.scripts.install, so it rides the CACHED vendor base (the heavy
                      app packages download once, not every build).
     write-catalog!   projects the specs to apps.edn — pure file write, host-runnable for
                      dev. Called from tools.scripts.desktop (image build + live
                      `dev push desktop`), so a catalog-only edit ships without a rebuild.
     stage-defaults!  overlays each app's assets/apps/<id>/rootfs defaults tree onto / —
                      first-run config, demo payloads, SPA builds; a path in the tree IS its
                      destination. Called beside write-catalog!, so a defaults edit ships
                      without a rebuild too.

   Pipeline: install (-> install!) -> base -> agent -> desktop (-> write-catalog!) -> ujimaify.

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! sh! with-console-out]]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.pprint :as pp]))


(def apps
  "Catalog specs + install recipes, in launcher order. :category files the app into an eww
   tray — one of :learn :office :create :web-files (the four the launcher renders); any other
   value renders in no tray. Install recipe is :apt [pkg …] or :fetch {:url :sha256 :dest}.
   :class is the WM_CLASS res_class i3 adopts on (a wrong one = the window never adopts and the
   app respawns forever); the apt apps' values are xprop-verified on hardware."
  [{:id :wikipedia :label "Wikipedia" :icon "wikipedia" :category :learn
    :exec ["/opt/ujima/desktop/bin/ujima-open-web-app" "https://wikipedia.com" "ujima-wikipedia"]
    :class "ujima-wikipedia" :apt ["chromium"]}

   ;; STUB: Kolibri (Learning Equality's offline learning platform) served locally on its default
   ;; :8080 — the server isn't stood up yet, so the tile opens a placeholder until it is.
   {:id :kolibri :label "Kolibri" :icon "kolibri" :category :learn
    :exec ["/opt/ujima/desktop/bin/ujima-open-web-app" "http://localhost:8080" "ujima-kolibri"]
    :class "ujima-kolibri" :apt ["chromium"]}

   ;; each LibreOffice tile gets its own -env:UserInstallation so it's a SEPARATE soffice process
   ;; -> its own scope, independently killable. Otherwise all three share one soffice and only the
   ;; first tile's scope would be real.
   {:id :write :label "Write" :icon "write" :category :office
    :exec ["libreoffice" "-env:UserInstallation=file:///home/ujima/.config/ujima-lo-write" "--writer"]
    :class "libreoffice-writer" :apt ["libreoffice-writer" "libreoffice-gtk3"]}

   {:id :calc :label "Calc" :icon "calc" :category :office
    :exec ["libreoffice" "-env:UserInstallation=file:///home/ujima/.config/ujima-lo-calc" "--calc"]
    :class "libreoffice-calc" :apt ["libreoffice-calc"]}         ; xprop-verified

   {:id :impress :label "Impress" :icon "impress" :category :office
    :exec ["libreoffice" "-env:UserInstallation=file:///home/ujima/.config/ujima-lo-impress" "--impress"]
    :class "libreoffice-impress" :apt ["libreoffice-impress"]}   ; xprop-verified

   ;; ONLYOFFICE Desktop Editors (arm64 .deb, offline-first): NOT in Debian, so a pinned :deb
   ;; fetched+apt-installed at build. Launch via the /usr/bin wrapper (raw binary skips Qt env).
   {:id :onlyoffice :label "ONLYOFFICE" :icon "onlyoffice" :category :office
    :exec ["onlyoffice-desktopeditors"]
    :class "ONLYOFFICE"                                          ; res_class, xprop-verified
    :deb {:url    "https://github.com/ONLYOFFICE/DesktopEditors/releases/download/v9.4.0/onlyoffice-desktopeditors_arm64.deb"
          :sha256 "ce141a103051e220a89839dd5dc8511172ae5b989e8de9bda0e07c34b0b7702c"}}

   ;; --nolockfile: TuxPaint otherwise refuses to start within 30s of its last launch, so a
   ;; close-and-reopen silently does nothing.
   {:id :draw :label "Draw" :icon "draw" :category :create
    :exec ["tuxpaint" "--nolockfile"]
    :class "TuxPaint.TuxPaint" :apt ["tuxpaint"]}

   ;; GIMP 3.0 (GTK3): full raster editor. Ships its OWN dark theme (default) so it's dark with no
   ;; Qt/gtk-platformtheme bridge, single-window mode by default, traditional menubar (clean under the
   ;; eww topbar). First-run "Welcome" dialog to suppress in the app-config pass.
   {:id :gimp :label "GIMP" :icon "gimp" :category :create
    :exec ["gimp"]
    :class "Gimp" :apt ["gimp"]}                             ; xprop-verified (res_class)

   {:id :files :label "Files" :icon "files" :category :system
    :exec ["pcmanfm" "/home/ujima/Files"]
    :class "Pcmanfm" :apt ["pcmanfm"]}                       ; res_class (xprop-verified: instance pcmanfm / class Pcmanfm)

   {:id :web :label "Web" :icon "web" :category :explore
    :exec ["chromium" "--class=ujima-web" "--user-data-dir=/tmp/ujima-web" "--no-first-run"]
    :class "ujima-web" :apt ["chromium"]}                        ; vanilla full-UI browser (not --app)

   {:id :inkscape :label "Inkscape" :icon "inkscape" :category :create
    :exec ["inkscape"]
    :class "Inkscape" :apt ["inkscape"]}                     ; xprop-verified (res_class)

   {:id :turbowarp :label "TurboWarp" :icon "scratch" :category :code
    :exec ["/opt/ujima/apps/turbowarp/turbowarp-desktop" "--no-sandbox"]
    :class "turbowarp-desktop"                               ; StartupWMClass (package.json)
    :fetch {:url    "https://github.com/TurboWarp/desktop/releases/download/v1.16.0/TurboWarp-linux-arm64-1.16.0.tar.gz"
            :sha256 "5909f02d92536c3ee52121dec4f1b7a73261a08ac7e091d15205cbff9893e33a"
            :dest   "/opt/ujima/apps/turbowarp"}}

   ;; Godot 4 (game engine): official arm64 editor, fetched + sha256-pinned like TurboWarp (a .zip
   ;; carrying one versioned binary → :bin renames it to a stable `godot`). Opens its editor straight
   ;; into the vendored 2D platformer demo (the assets/apps/godot defaults tree) — a wow-on-open
   ;; instead of an empty Project Manager. Forced to Vulkan Mobile: the V3D does Vulkan 1.3 but not the
   ;; Forward+/Clustered tier (<48 textures/stage), and Mobile is the right renderer for a Pi GPU
   ;; (needs mesa-vulkan-drivers, install.clj).
   {:id :godot :label "Godot" :icon "godot" :category :code
    :exec ["/opt/ujima/apps/godot/godot" "--editor" "--path" "/opt/ujima/apps/godot-demo"
           "--rendering-driver" "vulkan" "--rendering-method" "mobile"]
    :class "Godot"                                           ; res_class, xprop-verified
    :fetch {:url    "https://github.com/godotengine/godot/releases/download/4.7-stable/Godot_v4.7-stable_linux.arm64.zip"
            :sha256 "db5aa126353a18fd664818e4f1b9cfffaa77e32d4c9af0ea87e8f028a395a1ed"
            :dest   "/opt/ujima/apps/godot"
            :bin    "godot"}}

   ;; Excalidraw (offline whiteboard): a vendored static SPA build (assets/apps/excalidraw ->
   ;; /opt/ujima/apps/excalidraw/app), launched via ujima-serve-web-app — the SPA convention:
   ;; serve the build on localhost (python3 http.server STOPGAP; an ES-module app needs an http
   ;; origin, file:// is CORS-blocked), open it as a kiosk web app, reap the server on close.
   ;; dark-default baked into app/index.html. Collab reaches Excalidraw's public servers (no
   ;; offline) — a ujima fork will self-host the LAN relay. TODO: real static server.
   {:id :excalidraw :label "Excalidraw" :icon "excalidraw" :category :create
    :exec ["/opt/ujima/desktop/bin/ujima-serve-web-app"
           "/opt/ujima/apps/excalidraw/app" "index.html" "8090" "ujima-excalidraw"]
    :class "ujima-excalidraw" :apt ["python3"]}   ; python3 = the wrapper's stopgap http.server

   {:id :tuxtype :label "TuxTyping" :icon "tuxtype" :category :learn
    :exec ["/usr/games/tuxtype"]                            ; /usr/games isn't on the service PATH
    :class "tuxtype" :apt ["tuxtype"]}                       ; xprop-verified

   {:id :thonny :label "Thonny" :icon "thonny" :category :code
    :exec ["thonny"]
    :class "Thonny" :apt ["thonny"]}                         ; xprop-verified

   {:id :geany :label "Geany" :icon "geany" :category :code
    :exec ["geany"]
    :class "Geany" :apt ["geany"]}                           ; xprop-verified

   ;; NO :mode :fullscreen — Stellarium sets the fullscreen state itself, so detection hides the
   ;; bars only when it's REALLY fullscreen. Declaring it hid the bars whenever Stellarium was
   ;; focused, trapping the user behind its non-fullscreen dialogs.
   {:id :stellarium :label "Stellarium" :icon "stellarium" :category :explore
    :exec ["stellarium"]
    :class "stellarium" :apt ["stellarium"]}                 ; xprop-verified

   ;; Marble (KDE virtual globe): offline Earth/planets atlas. Qt app → dark via qt6-gtk-platformtheme
   ;; (install.clj) + the session's QT_QPA_PLATFORMTHEME. marble-qt is the Qt-only build (still pulls
   ;; QtWebEngine as a core dep); marble-data ships the offline Atlas/Satellite maps.
   {:id :marble :label "Marble" :icon "marble" :category :explore
    :exec ["marble-qt"]
    :class "Marble Virtual Globe" :apt ["marble-qt" "marble-data"]}  ; res_class xprop-verified

   ;; XaoS (real-time fractal zoomer): Mandelbrot/Julia exploration — Qt6, so it follows the Nordic
   ;; theme dark via qt6-gtk-platformtheme + the session's QT_QPA_PLATFORMTHEME.
   {:id :xaos :label "XaoS" :icon "xaos" :category :explore
    :exec ["xaos"]
    :class "XaoS" :apt ["xaos"]}])                           ; res_class xprop-verified


;; :mode (e.g. :fullscreen) is a projection hint: a fixed-fullscreen app hides the bars.
;; :class is the WM_CLASS the agent hands i3 to route an orphaned window to its workspace — a
;; placement hint, NOT an identity key (identity is still the workspace); the agent never matches
;; windows by it, it only tells i3 where a matching window belongs.
(def ^:private spec-keys [:id :label :icon :category :exec :mode :class])


(defn- fetch!
  "Download a prebuilt payload, verify its sha256, unpack into :dest. A .tar.gz strips the
   tarball's top dir (app-dir tarballs like TurboWarp); a .zip unzips flat, and with :bin the one
   extracted file is renamed to :dest/<bin> for a stable exec path (Godot ships a single versioned
   binary in a zip). The git-free equivalent of an apt app (vendored at build, never committed)."
  [{:keys [url sha256 dest bin]}]
  (let [tmp (str "/tmp/" (fs/file-name url))]
    ($! curl -fSL [url] -o [tmp])
    (let [got (first (str/split (sh! :sha256sum tmp) #"\s+"))]
      (when-not (= got sha256)
        (throw (ex-info "app-catalog fetch sha256 mismatch"
                        {:url url :expected sha256 :got got}))))
    ($! rm -rf [dest])
    (fs/create-dirs dest)
    (if (str/ends-with? (str url) ".zip")
      (do ($! unzip -oq [tmp] -d [dest])
          (when bin                          ; rename the single payload → stable :dest/<bin>
            (let [f (->> (fs/list-dir dest) (map str) (remove #(= (fs/file-name %) bin)) first)]
              ($! mv [f] [(str dest "/" bin)])
              ($! chmod "0755" [(str dest "/" bin)]))))
      ($! tar -xzf [tmp] -C [dest] "--strip-components=1"))
    (fs/delete-if-exists tmp)))


(defn- deb!
  "Download a third-party .deb, verify its sha256, and apt-install it (apt resolves the .deb's
   dependencies from the base repos) — for packages not in Debian, e.g. ONLYOFFICE's arm64 build.
   Pinned + fetched at build like an apt app, never vendored in git."
  [{:keys [url sha256]}]
  (let [tmp (str "/tmp/" (fs/file-name url))]
    ($! curl -fSL [url] -o [tmp])
    (let [got (first (str/split (sh! :sha256sum tmp) #"\s+"))]
      (when-not (= got sha256)
        (throw (ex-info "app-catalog deb sha256 mismatch"
                        {:url url :expected sha256 :got got}))))
    (sh! "apt-get" "install" "-y" tmp)             ; a /path arg installs the local .deb + its deps
    (fs/delete-if-exists tmp)))


(defn install!
  "apt-install the deduped union of every :apt, fetch each :fetch payload, and apt-install each
   pinned :deb. Assumes apt is already updated (tools.scripts.install runs `apt-get update` once)."
  [_]
  (let [pkgs (->> apps (mapcat :apt) (remove nil?) distinct vec)]
    (apply sh! "apt-get" "install" "-y" "--no-install-recommends" pkgs))
  (when (some #(or (:fetch %) (:deb %)) apps)
    ($! apt-get install -y --no-install-recommends "curl" "ca-certificates")
    (when (some #(some-> % :fetch :url (str/ends-with? ".zip")) apps)
      ($! apt-get install -y --no-install-recommends "unzip"))
    (doseq [{:keys [fetch deb]} apps]
      (when fetch (fetch! fetch))
      (when deb (deb! deb)))))


(defn write-catalog!
  "Emit the launcher catalog (apps.edn) from `apps` — spec fields only, install recipes
   dropped. Pure file write, no root; host-runnable (the `app-catalog` bb task)."
  [{:keys [dest] :or {dest "/opt/ujima/desktop/apps.edn"}}]
  (let [specs (mapv #(select-keys % spec-keys) apps)]
    (fs/create-dirs (str (fs/parent dest)))
    (spit dest (str ";; GENERATED by tools.scripts.appcatalog/write-catalog! — do not edit.\n"
                    ";; Source of truth: tools/src/tools/scripts/appcatalog.clj (the `apps` vector).\n"
                    (with-out-str (pp/pprint {:apps specs}))))
    (println "app-catalog: wrote" (count specs) "apps ->" dest)))


(defn- overlay!
  "Copy ROOTFS onto / entry-by-entry: a dir is only created when missing — an existing dir's
   ownership/mode is NEVER touched. (cp -a of the tree root applies the staged tree's attrs to
   every dir it merges through: it root-owned /home/ujima on HW, and xauth can't lock inside a
   root-owned home -> no X cookie -> dead session.) Files + symlinks ride cp -a (perms, exec
   bit); {:hidden true} or the glob silently drops dotfiles like .stellarium."
  [rootfs]
  (doseq [p (sort-by str (fs/glob rootfs "**" {:hidden true}))
          :let [target (str "/" (fs/relativize rootfs p))]]
    (if (fs/directory? p {:nofollow-links true})
      (fs/create-dirs target)
      ($! cp -a [(str p)] [target]))))


(defn- ujima-owned
  "The paths a rootfs stages that must belong to the ujima user: entries directly under
   home/ujima, and entries one level inside an /opt/ujima area dir (opt/ujima/<area>/<entry>) —
   never the area itself, so a tree can't seize /opt/ujima/apps from the payloads beside its own."
  [rootfs]
  (let [home (fs/path rootfs "home/ujima")
        opt  (fs/path rootfs "opt/ujima")]
    (->> (concat (when (fs/exists? home) (fs/list-dir home))
                 (when (fs/exists? opt)
                   (mapcat #(if (fs/directory? %) (fs/list-dir %) [%]) (fs/list-dir opt))))
         (map #(str "/" (fs/relativize rootfs %))))))


(defn stage-defaults!
  "Overlay each app's assets/apps/<id>/rootfs onto / — a path in the tree IS its destination.
   Per-app defaults (first-run config, demo payloads, SPA builds) live there, so adding one is a
   file drop, not a build-script edit. Staged /home/ujima + /opt/ujima paths become ujima-owned
   (apps rewrite their own config; the repo's uids are the build host's). A dir without a
   catalog :id or a rootfs/ throws — a typo must never stage nothing, silently."
  [{:keys [project]}]
  (let [root (fs/path (str project) "assets/apps")
        ids  (into #{} (map (comp name :id)) apps)]
    (doseq [dir (when (fs/exists? root) (sort-by str (fs/list-dir root)))
            :let [id     (fs/file-name dir)
                  rootfs (fs/path dir "rootfs")]]
      (when-not (contains? ids id)
        (throw (ex-info "assets/apps dir has no catalog entry" {:dir id})))
      (when-not (fs/exists? rootfs)
        (throw (ex-info "assets/apps dir has no rootfs/" {:dir id})))
      (overlay! rootfs)
      (doseq [p (ujima-owned rootfs)]
        ($! chown -R "ujima:ujima" [p]))
      (println "app-defaults:" id "staged"))))


(defn run!
  "Standalone full pass: apt-install + fetch, then stage defaults + write the catalog. The image
   build reaches install! via tools.scripts.install (cached) and the other two via
   tools.scripts.desktop; this run! is the manual `tools image/dev script appcatalog`."
  [opts]
  (with-console-out
    ($! apt-get update)
    (install! opts)
    (stage-defaults! opts)
    (write-catalog! opts)))
