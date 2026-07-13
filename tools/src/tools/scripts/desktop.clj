(ns tools.scripts.desktop
  "Runs INSIDE the target chroot as root (and is the live `dev push desktop` deploy path). Stages
   the ujima *desktop* layer — its config files + assets — onto the base.

   SCAFFOLD: the desktop layer doesn't exist yet. This stages assets/desktop into /opt/ujima/desktop
   when that dir is present, and is otherwise a no-op. The graphical session's systemd unit will
   live in tools.scripts.ujimaify; runtime desktop *settings* (wallpaper, resolution, …) are the
   agent's job at runtime, not this build script.

   Pipeline: install -> base -> agent -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]
            [tools.scripts.appcatalog :as appcatalog]))


(defn run! [{:keys [project]}]
  (with-console-out
    (let [src (str project "/assets/desktop")]
      (if (fs/exists? src)
        (do                                   ;; clean-mirror, preserving the exec bit (cp -a)
          (fs/create-dirs "/opt/ujima")
          ($! rm -rf "/opt/ujima/desktop")
          ($! cp -a [src] "/opt/ujima/desktop"))
        (println "desktop: no assets/desktop yet — scaffold no-op")))

    ;; desktop background: rasterize the vector wall.svg -> a ≥1080p PNG for feh (the X root can't
    ;; take an SVG). Uses the librsvg gdk-pixbuf loader via python3-gi — both installed by
    ;; tools.scripts.install. wall.svg is the editable source; wall.png is what i3's `exec feh` sets.
    (when (fs/exists? "/opt/ujima/desktop/wall.svg")
      ($! python3 "-c"
          (str "import gi; gi.require_version('GdkPixbuf','2.0'); from gi.repository import GdkPixbuf; "
               "GdkPixbuf.Pixbuf.new_from_file_at_scale('/opt/ujima/desktop/wall.svg',1920,1200,False)"
               ".savev('/opt/ujima/desktop/wall.png','png',[],[])")))

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

    ;; per-app home config (first-launch suppression + sane defaults) → the ujima user's home.
    ;; e.g. assets/home/.stellarium/config.ini disables Stellarium's startup online-catalog updates
    ;; that otherwise hang its window offline. Owned by ujima so the apps can read + rewrite them.
    (let [home (str project "/assets/home")]
      (if (fs/exists? home)
        (do ($! cp -a [(str home "/.")] "/home/ujima/")
            ($! chown -R "ujima:ujima" "/home/ujima"))
        (println "desktop: no assets/home — app home configs not staged")))

    ;; Godot demo project — the launcher opens Godot's editor into this (the "wow" 2D platformer),
    ;; not an empty Project Manager. Vendored source + editor state (2D main-screen); the import cache
    ;; is NOT committed, so Godot re-imports at runtime — writes land in the overlay upper. Staged
    ;; beside the fetched godot binary in baked-apps (survives the desktop/ clean-mirror above).
    (let [demo (str project "/assets/godot-demo")]
      (if (fs/exists? demo)
        (do (fs/create-dirs "/opt/ujima/baked-apps")
            ($! rm -rf "/opt/ujima/baked-apps/godot-demo")
            ($! cp -a [demo] "/opt/ujima/baked-apps/godot-demo")
            ;; cp -a kept the build-host uid (1000); runtime user is ujima (1001) → the demo would be
            ;; unwritable, so Godot can't write res://.godot (caches/saves) → black editor + won't close.
            ($! chown -R "ujima:ujima" "/opt/ujima/baked-apps/godot-demo"))
        (println "desktop: no assets/godot-demo — Godot demo not staged")))

    ;; web apps (Excalidraw): vendored static builds + their launch wrappers → /opt/ujima/web. Each
    ;; app's stopgap wrapper serves its dir with python3's http.server + opens it as a chromium app.
    (let [web (str project "/assets/web")]
      (if (fs/exists? web)
        (do ($! rm -rf "/opt/ujima/web")
            ($! cp -a [web] "/opt/ujima/web"))
        (println "desktop: no assets/web — web apps not staged")))

    ;; url handler: xdg-open (via mimeapps.list, staged with assets/home) resolves http/https to
    ;; this .desktop -> bin/ujima-open -> the Web app.
    (fs/create-dirs "/usr/share/applications")
    (spit "/usr/share/applications/ujima-open.desktop"
          (str "[Desktop Entry]\nType=Application\nName=Ujima URL Handler\n"
               "Exec=/opt/ujima/desktop/bin/ujima-open %u\n"
               "MimeType=x-scheme-handler/http;x-scheme-handler/https;text/html;\nNoDisplay=true\n"))

    ;; the launcher catalog is GENERATED (not a committed asset), from tools.scripts.appcatalog.
    ;; Emitted AFTER the wholesale copy above so the clean-mirror can't clobber it; rides both the
    ;; image build and live `dev push desktop`, so a catalog-only edit ships without a rebuild.
    (appcatalog/write-catalog! {:dest "/opt/ujima/desktop/apps.edn"})))
