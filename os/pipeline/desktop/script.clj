(ns pipeline.desktop.script
  "Runs INSIDE the target chroot as root (and is the live `dev push desktop` deploy path).
   Stages the ujima *desktop* layer — the desktop/ tree, plus its concern files under
   os/pipeline/desktop/ (theme, fonts, links, files, eww, i18n) — onto the base. The graphical
   session's systemd unit lives in the ujimaify stage; runtime desktop *settings* (wallpaper,
   resolution, …) are ujimad's job at runtime, not this build script.

   Pipeline: install -> boot -> base -> runtime -> desktop -> ujimaify -> [dev] -> [cleanup].

   `project` is the read-only repo bind inside the chroot (default /ujima-src)."
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [build.apps :as apps]
            [pipeline.desktop.i18n :as i18n]
            [build.files :as files]))


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
    ;; the install stage. wall.svg is the editable source; wall.png is what i3's `exec feh` sets.
    (when (fs/exists? "/ujima/desktop/shell/wall.svg")
      ($! python3 "-c"
          (str "import gi; gi.require_version('GdkPixbuf','2.0'); from gi.repository import GdkPixbuf; "
               "GdkPixbuf.Pixbuf.new_from_file_at_scale('/ujima/desktop/shell/wall.svg',1920,1200,False)"
               ".savev('/ujima/desktop/shell/wall.png','png',[],[])")))

    ;; eww binary: built out-of-band on a Pi (build-eww, dev kit) and vendored as the
    ;; single tracked file — versions live in git history. Staged here (not install) so a
    ;; rebuilt eww ships via `dev push desktop` without rebuilding the cached vendor base.
    (files/install! project "desktop/eww/eww" "/usr/local/bin/eww")

    ;; the skin: Public Sans (shell face) + Nordic + the GTK defaults that point at them
    ;; (the why lives in theme/settings.ini)
    (files/mirror! project "desktop/fonts/public-sans" "/usr/share/fonts/truetype/public-sans")
    ($! fc-cache -f)
    (files/mirror! project "desktop/theme/Nordic" "/usr/share/themes/Nordic")
    (files/install! project "desktop/theme/settings.ini" "/etc/gtk-3.0/settings.ini")

    ;; GTK chooser label override ("Home" -> "Temporary"; the why lives in the catalog):
    ;; the .mo is GENERATED here from the catalog — no vendored binary, no gettext in the
    ;; chroot (desktop.i18n writes the format directly). One catalog, two locale installs.
    (let [mo (i18n/mo-bytes (edn/read-string
                             (slurp (files/source project "desktop/i18n/catalog.edn"))))]
      (doseq [loc ["en_GB" "en_US"]]
        (fs/create-dirs (str "/usr/share/locale/" loc "/LC_MESSAGES"))
        (with-open [out (io/output-stream (str "/usr/share/locale/" loc "/LC_MESSAGES/gtk30.mo"))]
          (.write out mo))))

    ;; session-level home seeds → the ujima user's home (per-APP home defaults live in their
    ;; apps trees, staged below): links routing + the Files-plane defaults. install! creates
    ;; parent dirs as root — the chown heals them (apps + xdg rewrite these as ujima).
    (files/install! project "desktop/links/mimeapps.list"
                    "/home/ujima/.config/mimeapps.list" {:owner "ujima:ujima"})
    (files/install! project "desktop/files/user-dirs.dirs"
                    "/home/ujima/.config/user-dirs.dirs" {:owner "ujima:ujima"})
    (files/install! project "desktop/files/bookmarks"
                    "/home/ujima/.config/gtk-3.0/bookmarks" {:owner "ujima:ujima"})
    ($! chown -R "ujima:ujima" "/home/ujima/.config")

    ;; the Files-area tmpfiles half (kid-facing /ujima/storage/files) — the files plane is
    ;; desktop's; the /ujima/run half stays with ujimaify's layout concern
    (files/install! project "desktop/files/ujima-files.conf" "/etc/tmpfiles.d/ujima-files.conf")

    ;; url handler registration (routing story lives in links/ujima-open-url.desktop)
    (files/install! project "desktop/links/ujima-open-url.desktop"
                    "/usr/share/applications/ujima-open-url.desktop")

    ;; the packaged app set (os/apps/<id>): app.edn specs -> the catalog scan root, rootfs/
    ;; defaults overlaid onto / — AFTER the mirrors above so a clean-mirror can't clobber
    ;; them; rides this same script live (`dev script desktop`), so an app edit ships
    ;; without a rebuild.
    (apps/stage-defaults! project)))
