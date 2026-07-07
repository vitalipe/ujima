(ns tools.scripts.appcatalog
  "Single source of truth for the launcher apps. Each entry pairs the catalog SPEC the eww
   launcher/dock render + launch with its build-time install recipe (:apt packages or a
   :fetch payload). Packages are never vendored in git — they install like apt at build
   time; only the spec lives here. Adding an app is one entry.

   Two build seams share this one data source:
     install!        apt-installs every :apt (deduped) + fetches each :fetch. Called from
                     tools.scripts.install, so it rides the CACHED vendor base (the heavy
                     app packages download once, not every build).
     write-catalog!  projects the specs to apps.edn — pure file write, host-runnable for
                     dev. Called from tools.scripts.desktop (image build + live
                     `dev push desktop`), so a catalog-only edit ships without a rebuild.

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
    :exec ["chromium" "--app=https://wikipedia.com" "--class=ujima-wikipedia"
           "--user-data-dir=/tmp/ujima-wikipedia" "--no-first-run" "--disk-cache-size=1"]
    :class "ujima-wikipedia" :apt ["chromium"]}

   {:id :write :label "Write" :icon "write" :category :office
    :exec ["libreoffice" "--writer"]
    :class "libreoffice-writer" :apt ["libreoffice-writer" "libreoffice-gtk3"]}

   {:id :calc :label "Calc" :icon "calc" :category :office
    :exec ["libreoffice" "--calc"]
    :class "libreoffice-calc" :apt ["libreoffice-calc"]}         ; xprop-verified

   {:id :impress :label "Impress" :icon "impress" :category :office
    :exec ["libreoffice" "--impress"]
    :class "libreoffice-impress" :apt ["libreoffice-impress"]}   ; xprop-verified

   {:id :draw :label "Draw" :icon "draw" :category :create
    :exec ["tuxpaint"]
    :class "TuxPaint.TuxPaint" :apt ["tuxpaint"]}

   {:id :files :label "Files" :icon "files" :category :web-files
    :exec ["pcmanfm" "/home/ujima/Files"]
    :class "pcmanfm" :apt ["pcmanfm"]}

   {:id :web :label "Web" :icon "web" :category :web-files
    :exec ["chromium" "--class=ujima-web" "--user-data-dir=/tmp/ujima-web" "--no-first-run"]
    :class "ujima-web" :apt ["chromium"]}                        ; vanilla full-UI browser (not --app)

   {:id :inkscape :label "Inkscape" :icon "inkscape" :category :create
    :exec ["inkscape"]
    :class "Inkscape" :apt ["inkscape"]}                     ; xprop-verified (res_class)

   {:id :turbowarp :label "TurboWarp" :icon "scratch" :category :create
    :exec ["/opt/ujima/baked-apps/turbowarp/turbowarp-desktop" "--no-sandbox"]
    :class "turbowarp-desktop"                               ; StartupWMClass (package.json)
    :fetch {:url    "https://github.com/TurboWarp/desktop/releases/download/v1.16.0/TurboWarp-linux-arm64-1.16.0.tar.gz"
            :sha256 "5909f02d92536c3ee52121dec4f1b7a73261a08ac7e091d15205cbff9893e33a"
            :dest   "/opt/ujima/baked-apps/turbowarp"}}

   {:id :tuxtype :label "TuxTyping" :icon "tuxtype" :category :learn
    :exec ["/usr/games/tuxtype"]                            ; /usr/games isn't on the service PATH
    :class "tuxtype" :apt ["tuxtype"]}                       ; xprop-verified

   {:id :thonny :label "Thonny" :icon "thonny" :category :create
    :exec ["thonny"]
    :class "Thonny" :apt ["thonny"]}                         ; xprop-verified

   {:id :geany :label "Geany" :icon "geany" :category :create
    :exec ["geany"]
    :class "Geany" :apt ["geany"]}                           ; xprop-verified

   {:id :stellarium :label "Stellarium" :icon "stellarium" :category :learn
    :exec ["stellarium"]
    :class "stellarium" :apt ["stellarium"]}])               ; xprop-verified


(def ^:private spec-keys [:id :label :icon :category :exec :class])


(defn- fetch!
  "Download a prebuilt payload, verify its sha256, unpack into :dest stripping the tarball's
   top dir — the git-free equivalent of an apt app (vendored at build, never committed)."
  [{:keys [url sha256 dest]}]
  (let [tmp (str "/tmp/" (fs/file-name url))]
    ($! curl -fSL [url] -o [tmp])
    (let [got (first (str/split (sh! :sha256sum tmp) #"\s+"))]
      (when-not (= got sha256)
        (throw (ex-info "app-catalog fetch sha256 mismatch"
                        {:url url :expected sha256 :got got}))))
    ($! rm -rf [dest])
    (fs/create-dirs dest)
    ($! tar -xzf [tmp] -C [dest] "--strip-components=1")
    (fs/delete-if-exists tmp)))


(defn install!
  "apt-install the deduped union of every :apt, then run each :fetch. Assumes apt is already
   updated (tools.scripts.install runs `apt-get update` once before calling us)."
  [_]
  (let [pkgs (->> apps (mapcat :apt) (remove nil?) distinct vec)]
    (apply sh! "apt-get" "install" "-y" "--no-install-recommends" pkgs))
  (when (some :fetch apps)
    ($! apt-get install -y --no-install-recommends "curl" "ca-certificates")
    (doseq [{:keys [fetch]} apps :when fetch]
      (fetch! fetch))))


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


(defn run!
  "Standalone full pass: apt-install + fetch, then write the catalog. The image build reaches
   install! via tools.scripts.install (cached) and write-catalog! via tools.scripts.desktop;
   this run! is the manual `tools image script appcatalog` / `tools dev script appcatalog`."
  [opts]
  (with-console-out
    ($! apt-get update)
    (install! opts)
    (write-catalog! opts)))
