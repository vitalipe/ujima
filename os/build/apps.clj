(ns build.apps
  "Build glue for the packaged app set — os/apps/<id>/, third-party software packaged
   for ujima. An app is ONE dir: app.edn = the runtime artifact (staged to the device
   scan root, read by ujimad at boot — never grows build keys), install.edn = the build
   input (apt/fetch/deb recipe — never ships), rootfs/ = first-run defaults overlaid
   onto /, icon.svg + app/ = its face and payload. Adding an app = one dir, no central
   edits; the dirs ARE the list.

   Two build seams consume this, like build.files:
     install!         the install stage — apt union + pinned fetches/debs, so the heavy
                      app packages bake into the CACHED vendor base
     stage-defaults!  the desktop stage (image build + live `dev script desktop`) — an
                      app edit ships without a rebuild"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [lib.shell :refer [$! sh!]]))


(def ^:private apps-root "os/apps")


(defn- app-dirs [project]
  (->> (fs/list-dir (fs/path (str project) apps-root))
       (filter fs/directory?)
       (sort-by (comp str fs/file-name))))


(defn- recipe!
  "The dir's install.edn as {:id \"gimp\" :apt [..]} — REQUIRED, so a typo'd or empty
   dir can never stage nothing, silently."
  [dir]
  (let [f (fs/path dir "install.edn")]
    (when-not (fs/exists? f)
      (throw (ex-info "app dir has no install.edn" {:dir (str (fs/file-name dir))})))
    (assoc (edn/read-string (slurp (str f))) :id (str (fs/file-name dir)))))


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
        (throw (ex-info "app install.edn fetch sha256 mismatch"
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
        (throw (ex-info "app install.edn deb sha256 mismatch"
                        {:url url :expected sha256 :got got}))))
    (sh! "apt-get" "install" "-y" tmp)             ; a /path arg installs the local .deb + its deps
    (fs/delete-if-exists tmp)))


(defn install!
  "apt-install the deduped union of every :apt, fetch each :fetch payload, and apt-install each
   pinned :deb. Assumes apt is already updated (the install stage runs `apt-get update` once)."
  [project]
  (let [rs   (mapv recipe! (app-dirs project))
        pkgs (->> rs (mapcat :apt) (remove nil?) distinct vec)]
    (apply sh! "apt-get" "install" "-y" "--no-install-recommends" pkgs)
    (when (some #(or (:fetch %) (:deb %)) rs)
      ($! apt-get install -y --no-install-recommends "curl" "ca-certificates")
      (when (some #(some-> % :fetch :url (str/ends-with? ".zip")) rs)
        ($! apt-get install -y --no-install-recommends "unzip"))
      (doseq [{:keys [fetch deb]} rs]
        (when fetch (fetch! fetch))
        (when deb (deb! deb))))))


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
   home/ujima, and entries directly inside ujima/apps — never the area itself, so a tree
   can't seize /ujima/apps. ujima/apps is the only /ujima area a tree may stage into."
  [rootfs]
  (let [home (fs/path rootfs "home/ujima")
        apps (fs/path rootfs "ujima/apps")]
    (->> (concat (when (fs/exists? home) (fs/list-dir home))
                 (when (fs/exists? apps) (fs/list-dir apps)))
         (map #(str "/" (fs/relativize rootfs %))))))


(defn stage-defaults!
  "Stage each os/apps/<id> app onto the device: app.edn (+ its optional icon.svg) ->
   /ujima/apps/<id>/ (the scan root ujimad's boot catalog reads) and the optional
   rootfs/ tree overlaid onto / — first-run config, demo payloads, SPA builds; a path in
   the tree IS its destination. Staged /home/ujima + /ujima/apps paths become
   ujima-owned (apps rewrite their own config; the repo's uids are the build host's).
   Validates every dir whole: install.edn required (recipe!), and app.edn-or-rootfs
   required — a typo must never stage nothing, silently."
  [project]
  (doseq [dir (app-dirs project)
          :let [id      (str (fs/file-name dir))
                rootfs  (fs/path dir "rootfs")
                app-edn (fs/path dir "app.edn")]]
    (recipe! dir)
    (when-not (or (fs/exists? app-edn) (fs/exists? rootfs))
      (throw (ex-info "app dir has neither app.edn nor rootfs/" {:dir id})))
    (when (fs/exists? app-edn)
      (fs/create-dirs (str "/ujima/apps/" id))
      ($! cp -a [(str app-edn)] [(str "/ujima/apps/" id "/app.edn")])
      (let [icon (fs/path dir "icon.svg")]      ; the app dir owns its face (optional)
        (when (fs/exists? icon)
          ($! cp -a [(str icon)] [(str "/ujima/apps/" id "/icon.svg")])))
      (let [payload (fs/path dir "app")]        ; a :web-app's served build (optional)
        (when (fs/exists? payload)
          ($! cp -a [(str payload)] [(str "/ujima/apps/" id "/")]))))
    (when (fs/exists? rootfs)
      (overlay! rootfs)
      (doseq [p (ujima-owned rootfs)]
        ($! chown -R "ujima:ujima" [p])))
    (println "app-defaults:" id "staged")))
