(ns os.desktop
  "Runs INSIDE the target chroot as root (and is the live `dev push desktop` deploy path). Stages
   the ujima *desktop* layer — its config files + assets — onto the base.

   SCAFFOLD: the desktop layer doesn't exist yet. This stages desktop/ into /ujima/desktop
   when that dir is present, and is otherwise a no-op. The graphical session's systemd unit will
   live in os.ujimaify; runtime desktop *settings* (wallpaper, resolution, …) are
   ujimad's job at runtime, not this build script.

   Pipeline: install -> base -> ujimad -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]
            [os.appcatalog :as appcatalog]
            [os.lib.stage :as stage]))


(defn run! [{:keys [project]}]
  (with-console-out
    (let [src (str project "/desktop")]
      (if (fs/exists? src)
        (do                                   ;; clean-mirror, preserving the exec bit (cp -a)
          (fs/create-dirs "/ujima")
          ($! rm -rf "/ujima/desktop")
          ($! cp -a [src] "/ujima/desktop"))
        (println "desktop: no desktop/ yet — scaffold no-op")))

    ;; desktop background: rasterize the vector wall.svg -> a ≥1080p PNG for feh (the X root can't
    ;; take an SVG). Uses the librsvg gdk-pixbuf loader via python3-gi — both installed by
    ;; os.install. wall.svg is the editable source; wall.png is what i3's `exec feh` sets.
    (when (fs/exists? "/ujima/desktop/wall.svg")
      ($! python3 "-c"
          (str "import gi; gi.require_version('GdkPixbuf','2.0'); from gi.repository import GdkPixbuf; "
               "GdkPixbuf.Pixbuf.new_from_file_at_scale('/ujima/desktop/wall.svg',1920,1200,False)"
               ".savev('/ujima/desktop/wall.png','png',[],[])")))

    ;; eww binary: built out-of-band on a Pi (assets/dev/build-eww) and vendored as
    ;; assets/eww/eww-aarch64-latest. Staged here (not install) so a rebuilt eww ships via
    ;; `dev push desktop` without rebuilding the cached vendor base. Warn-not-fail while eww is still
    ;; in progress; tighten to a hard fail once it's a locked dependency.
    (let [eww (str project "/assets/eww/eww-aarch64-latest")]
      (if (fs/exists? eww)
        ($! install -m "0755" [eww] "/usr/local/bin/eww")
        (println "desktop: no assets/eww/eww-aarch64-latest yet — eww not staged")))

    ;; shell typography + app-content theme: vendored data files, no apt — freeze-safe, and this
    ;; same script carries them to a live dev Pi. Public Sans is the shell face (eww.scss);
    ;; Nordic + /etc/gtk-3.0 give GTK app content (pcmanfm, LO, dialogs) the design's Nord side.
    (let [fonts (str project "/assets/fonts/public-sans")]
      (if (fs/exists? fonts)
        (do (fs/create-dirs "/usr/share/fonts/truetype")
            ($! rm -rf "/usr/share/fonts/truetype/public-sans")
            ($! cp -a [fonts] "/usr/share/fonts/truetype/public-sans")
            ($! fc-cache -f))
        (println "desktop: no assets/fonts/public-sans — shell font not staged")))

    (let [theme (str project "/assets/themes/Nordic")]
      (if (fs/exists? theme)
        (do (fs/create-dirs "/usr/share/themes")
            ($! rm -rf "/usr/share/themes/Nordic")
            ($! cp -a [theme] "/usr/share/themes/Nordic")
            (fs/create-dirs "/etc/gtk-3.0")
            (spit "/etc/gtk-3.0/settings.ini"
                  (str "[Settings]\n"
                       "gtk-theme-name=Nordic\n"
                       "gtk-application-prefer-dark-theme=1\n"
                       "gtk-font-name=Public Sans 10\n")))
        (println "desktop: no assets/themes/Nordic — app theme not staged")))

    ;; GTK chooser label override: "Home" -> "Temporary" (home IS the tmpfs upper — the label
    ;; states the truth; GTK hardcodes the sidebar entry, gettext is the only config-free
    ;; lever). REPLACES the distro's en_GB/en_US gtk3 catalogs — other GTK strings fall back
    ;; to their American msgids, accepted. Regenerate via assets/i18n/make-gtk-mo.py.
    (let [mo (str project "/assets/i18n/gtk30-ujima.mo")]
      (if (fs/exists? mo)
        (doseq [loc ["en_GB" "en_US"]]
          (fs/create-dirs (str "/usr/share/locale/" loc "/LC_MESSAGES"))
          ($! cp [mo] [(str "/usr/share/locale/" loc "/LC_MESSAGES/gtk30.mo")]))
        (println "desktop: no assets/i18n/gtk30-ujima.mo — chooser labels not staged")))

    ;; session-level home config → the ujima user's home (per-APP home defaults live in their
    ;; apps trees, staged below). e.g. .config/mimeapps.list routes links to the url
    ;; handler. Owned by ujima so apps + xdg can read/rewrite.
    (let [home (str project "/assets/home")]
      (if (fs/exists? home)
        (do ($! cp -a [(str home "/.")] "/home/ujima/")
            ($! chown -R "ujima:ujima" "/home/ujima"))
        (println "desktop: no assets/home — home configs not staged")))

    ;; the Files-area tmpfiles half (kid-facing /ujima/storage/files) — the files plane is
    ;; desktop's; the /ujima/run half stays with ujimaify's layout concern
    (stage/install! project "desktop/files/ujima-files.conf" "/etc/tmpfiles.d/ujima-files.conf")

    ;; url handler: xdg-open (via mimeapps.list, staged with assets/home) resolves http/https to
    ;; this .desktop -> bin/ujima-open-url -> the Web app.
    (fs/create-dirs "/usr/share/applications")
    (spit "/usr/share/applications/ujima-open-url.desktop"
          (str "[Desktop Entry]\nType=Application\nName=Ujima URL Handler\n"
               "Exec=/ujima/desktop/bin/ujima-open-url %u\n"
               "MimeType=x-scheme-handler/http;x-scheme-handler/https;text/html;\nNoDisplay=true\n"))

    ;; per-app trees (apps/<id>): app.edn specs -> the catalog scan root, rootfs/
    ;; defaults overlaid onto / — AFTER the mirrors above so a clean-mirror can't clobber
    ;; them; rides both the image build and live `dev script desktop`, so an app edit ships
    ;; without a rebuild.
    (appcatalog/stage-defaults! {:project project})))
