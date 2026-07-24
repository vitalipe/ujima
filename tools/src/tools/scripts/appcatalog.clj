(ns tools.scripts.appcatalog
  "Install recipes for the launcher apps. The catalog SPECS live with the apps themselves —
   assets/apps/<id>/app.edn, scanned by the agent at boot (ujima.desktop.app/load-catalog) —
   so this file holds only what the IMAGE BUILD needs: which packages/payloads to install.
   Packages are never vendored in git — they install like apt at build time. Adding an app =
   drop assets/apps/<id>/ (app.edn [+ rootfs/ defaults]) + one recipe entry here.

   Two build seams share this one data source:
     install!         apt-installs every :apt (deduped) + fetches each :fetch + installs
                      each pinned :deb. Called from tools.scripts.install, so it rides the
                      CACHED vendor base (the heavy app packages download once, not every
                      build).
     stage-defaults!  stages each app's app.edn into the on-device scan root
                      (/opt/ujima/apps/<id>/) and overlays its rootfs/ defaults tree onto
                      / — first-run config, demo payloads, SPA builds; a path in the tree IS
                      its destination. Called from tools.scripts.desktop (image build + live
                      `dev script desktop`), so an app edit ships without a rebuild.

   Pipeline: install (-> install!) -> base -> agent -> desktop (-> stage-defaults!) -> ujimaify.

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! sh! with-console-out]]
            [babashka.fs :as fs]
            [clojure.string :as str]))


(def apps
  "Install recipes, one per app; :id must match its assets/apps/<id>/ dir (launch spec =
   assets/apps/<id>/app.edn). :apt [pkg …], :fetch {:url :sha256 :dest [:bin]}, or
   :deb {:url :sha256}."
  [{:id :wikipedia  :apt ["chromium"]}
   {:id :kolibri    :apt ["chromium"]}       ; STUB tile — the local server isn't stood up yet
   {:id :write      :apt ["libreoffice-writer" "libreoffice-gtk3"]}
   {:id :calc       :apt ["libreoffice-calc"]}
   {:id :impress    :apt ["libreoffice-impress"]}

   ;; ONLYOFFICE Desktop Editors (arm64 .deb, offline-first): NOT in Debian, so a pinned :deb
   ;; fetched+apt-installed at build.
   {:id :onlyoffice
    :deb {:url    "https://github.com/ONLYOFFICE/DesktopEditors/releases/download/v9.4.0/onlyoffice-desktopeditors_arm64.deb"
          :sha256 "ce141a103051e220a89839dd5dc8511172ae5b989e8de9bda0e07c34b0b7702c"}}

   {:id :draw       :apt ["tuxpaint"]}
   {:id :gimp       :apt ["gimp"]}
   {:id :files      :apt ["pcmanfm"]}
   {:id :web        :apt ["chromium"]}
   {:id :inkscape   :apt ["inkscape"]}

   {:id :turbowarp
    :fetch {:url    "https://github.com/TurboWarp/desktop/releases/download/v1.16.0/TurboWarp-linux-arm64-1.16.0.tar.gz"
            :sha256 "5909f02d92536c3ee52121dec4f1b7a73261a08ac7e091d15205cbff9893e33a"
            :dest   "/opt/ujima/apps/turbowarp"}}

   ;; official arm64 editor, a .zip carrying one versioned binary → :bin renames it to a stable
   ;; `godot`; Vulkan Mobile needs mesa-vulkan-drivers (install.clj). The vendored demo project
   ;; rides the assets/apps/godot rootfs tree.
   {:id :godot
    :fetch {:url    "https://github.com/godotengine/godot/releases/download/4.7-stable/Godot_v4.7-stable_linux.arm64.zip"
            :sha256 "db5aa126353a18fd664818e4f1b9cfffaa77e32d4c9af0ea87e8f028a395a1ed"
            :dest   "/opt/ujima/apps/godot"
            :bin    "godot"}}

   {:id :excalidraw :apt ["python3"]}        ; the SPA wrapper's stopgap http.server
   {:id :tuxtype    :apt ["tuxtype"]}
   {:id :thonny     :apt ["thonny"]}
   {:id :geany      :apt ["geany"]}
   {:id :stellarium :apt ["stellarium"]}
   {:id :marble     :apt ["marble-qt" "marble-data"]}  ; Qt-only build; marble-data = offline maps
   {:id :xaos       :apt ["xaos"]}])


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
  "Stage each assets/apps/<id> app onto the device: app.edn (+ its optional icon.svg) ->
   /opt/ujima/apps/<id>/ (the scan root the agent's boot catalog reads) and the optional
   rootfs/ tree overlaid onto / —
   first-run config, demo payloads, SPA builds; a path in the tree IS its destination. Staged
   /home/ujima + /opt/ujima paths become ujima-owned (apps rewrite their own config; the
   repo's uids are the build host's). A dir without a recipe :id, or with neither app.edn nor
   rootfs/, throws — a typo must never stage nothing, silently."
  [{:keys [project]}]
  (let [root (fs/path (str project) "assets/apps")
        ids  (into #{} (map (comp name :id)) apps)]
    (doseq [dir (when (fs/exists? root) (sort-by str (fs/list-dir root)))
            :let [id      (fs/file-name dir)
                  rootfs  (fs/path dir "rootfs")
                  app-edn (fs/path dir "app.edn")]]
      (when-not (contains? ids id)
        (throw (ex-info "assets/apps dir has no install recipe" {:dir id})))
      (when-not (or (fs/exists? app-edn) (fs/exists? rootfs))
        (throw (ex-info "assets/apps dir has neither app.edn nor rootfs/" {:dir id})))
      (when (fs/exists? app-edn)
        (fs/create-dirs (str "/opt/ujima/apps/" id))
        ($! cp -a [(str app-edn)] [(str "/opt/ujima/apps/" id "/app.edn")])
        (let [icon (fs/path dir "icon.svg")]      ; the app dir owns its face (optional)
          (when (fs/exists? icon)
            ($! cp -a [(str icon)] [(str "/opt/ujima/apps/" id "/icon.svg")]))))
      (when (fs/exists? rootfs)
        (overlay! rootfs)
        (doseq [p (ujima-owned rootfs)]
          ($! chown -R "ujima:ujima" [p])))
      (println "app-defaults:" id "staged"))))


(defn run!
  "Standalone full pass: apt-install + fetch, then stage app.edn specs + defaults. The image
   build reaches install! via tools.scripts.install (cached) and stage-defaults! via
   tools.scripts.desktop; this run! is the manual `tools image/dev script appcatalog`."
  [opts]
  (with-console-out
    ($! apt-get update)
    (install! opts)
    (stage-defaults! opts)))
