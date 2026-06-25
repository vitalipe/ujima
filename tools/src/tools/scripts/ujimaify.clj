(ns tools.scripts.ujimaify
  "Runs INSIDE the target chroot as root. 'ujimaifies' the staged system: writes + enables the
   systemd units that turn the staged agent (and, later, desktop) into boot services, and stamps
   the build. Writing/enabling units lives here (rare); *restarting* them for a live iteration is
   the CLI's job (`dev push`), not this script. (fstab is written per-slot at install time —
   ujima.device.ab/install-into-slot! — not here.)

   Pipeline: install -> base -> agent -> desktop -> ujimaify -> [dev] -> [cleanup]."
  (:require [lib.shell :refer [$! with-console-out]]
            [babashka.fs :as fs]))


;; The agent's boot service. Root: the agent reconciles system settings (hostnamectl, localectl,
;; …) that need privilege. /usr/local/bin/bb is installed by tools.scripts.install; src + config
;; are staged by tools.scripts.agent. Restart=on-failure so a crash retries without hammering.
(def ^:private ujima-service
  (str "[Unit]\n"
       "Description=ujima agent\n"
       "After=network-online.target\n"
       "Wants=network-online.target\n"
       "\n"
       "[Service]\n"
       "Type=simple\n"
       "WorkingDirectory=/opt/ujima\n"
       "ExecStart=/usr/local/bin/bb -cp src -m ujima.core\n"
       "Restart=on-failure\n"
       "RestartSec=2\n"
       "\n"
       "[Install]\n"
       "WantedBy=multi-user.target\n"))


;; Persistent, capped journal — backed by the storage partition via slot->fstab's /var/log/journal
;; bind. Storage=persistent is explicit: `auto` can stay volatile even with the dir present.
(def ^:private journald-conf
  (str "[Journal]\n"
       "Storage=persistent\n"
       "SystemMaxUse=256M\n"))


(defn run! [_opts]
  (with-console-out
    ;; agent boot service (write + enable; restart-on-iterate is the CLI's job, not here)
    (spit "/etc/systemd/system/ujima.service" ujima-service)
    ($! systemctl enable "ujima")

    ;; persistent capped journal on storage
    (fs/create-dirs "/etc/systemd/journald.conf.d")
    (spit "/etc/systemd/journald.conf.d/ujima.conf" journald-conf)

    ;; build marker
    (spit "/etc/ujima-release"
          (str "built-at=" (java.time.Instant/now) "\n"))))
