# Changelog

All notable changes to Ujima, newest first.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
A release's version is its **git tag** (`vX.Y.Z`) — tags are the only source of
version truth; branch names and build labels may disagree.

## [0.3.0] - Unreleased

First app support: an app is a self-contained directory (`app.edn` + icon +
payload) discovered by a boot-time catalog scan.

### Added

- App kinds — `app.edn` declares `:kind` (`:exec` / `:web-app` / `:link`);
  spawn resolves it to a runnable (`app->runnable`) and launches with the app
  dir as cwd.
- Multi-root catalog — `/mnt/storage/apps` is scanned alongside the baked
  `/opt/ujima/apps`; an app dropped onto storage joins the launcher.
- Per-app icons — the app dir owns `icon.svg`, served at `GET /app/icon/<id>`.
- Web-app runtime scripts — `ujima-open-web-app` (one chromium kiosk wrapper
  for all web apps) and `ujima-serve-web-app` (serve a vendored SPA, open it,
  reap the server on close).
- App scopes run under no-new-privs — `sudo` can't elevate from inside an app.
- First-run app defaults — Thonny and Geany dark (Nord scheme), GIMP and
  Inkscape welcome popups off, LibreOffice first-run infobar off (per-profile
  `registrymodifications.xcu` seeds).
- Shell keyboard shortcuts — Alt+F4 closes the focused app (the top-bar ✕ path,
  double-press force-close included); Alt+Tab / Alt+Shift+Tab cycle the running
  apps in dock order, home not a stop (new verbs `POST /app/next` / `/app/prev`);
  Alt+Escape goes home, leaving the app running — the non-destructive exit. The
  chords keep working over fullscreen windows, where the bars hide.

### Changed

- The catalog is built at boot by scanning per-app `app.edn` files
  (`assets/apps/<id>/` baked to `/opt/ujima/apps/<id>/`); the app id is the
  directory name; a broken app dir is skipped and logged, never killing the
  session.
- Excalidraw launches through `ujima-serve-web-app`.
- `ujima-open` renamed to `ujima-open-url`.

### Removed

- The generated central `apps.edn` — per-app `app.edn` is the runtime truth;
  `appcatalog.clj` keeps only the install recipes.
- Excalidraw's bespoke `run.sh`.

### Fixed

- `systemctl restart ujima` now really cycles the session: `ExecStop` asks the
  agent to die, so the session unwinds in order and the VT frees. Before, the
  PAM/logind session survived the restart (the unit's own cgroup is empty),
  leaving an orphaned desktop serving old code while the unit crash-looped.

## [0.2.0] - 2026-07-14

The Ujima desktop shell — first tagged release: Raspberry Pi 5 image build,
read-only root, a babashka settings agent, an i3 + eww + WebKitGTK shell, and a
~20-app catalog.

- **Image build** — `bb tools` builds the flashable Pi image end-to-end: Debian
  trixie rootfs, staged install scripts, pinned packages, per-slot settings and
  shared storage under `/ujima`.
- **Read-only OS** — `/` locked via an overlayroot tmpfs overlay (baked
  initramfs, pinned machine-id); journald persists to storage, capped and
  priority-tagged.
- **Settings agent** — desired-vs-actual converge over path-vector setting
  keys; HTTP `/api` + `/ui` on `:1337`; pure event sources (audio, USB storage)
  feeding stateless policies; reads shell out to nothing.
- **Audio** — PipeWire layer: per-class volumes, machine-wide mute, output
  switching, hotplug converge on `[:audio :active]`.
- **Keyboard** — live layout switching via setxkbmap, control-owned.
- **App layer** — passive projection over i3: workspace = app identity,
  run/switch/close verbs, systemd `--user` scopes for liveness and force-kill,
  second same-app windows cover instead of tile, dialogs float with their app,
  fullscreen apps hide the bars.
- **Shell UI** — WebKitGTK launcher plus eww top bar and dock; charcoal skin,
  lucide icons, vendored Public Sans, colour-coded categories
  (Learn / Office / Create / Web & Files), gradient background.
- **App catalog** — ~20 education/office/creative apps with install recipes
  (`:apt` / `:fetch` / `:deb`): LibreOffice Writer/Calc/Impress, ONLYOFFICE,
  GIMP 3, Godot 4 (Vulkan Mobile + bundled 2D demo), Stellarium, Marble, XaoS,
  Excalidraw (offline PWA), Chromium, Kolibri stub, and more; per-app first-run
  defaults staged from `assets/apps/<id>/rootfs`; Qt apps themed dark.
- **Cold boot** — X-auth race and hostname-converge stall fixed; power-on to
  desktop ~85s → ~20-25s.
- **Dev rig** — live `dev push` / `dev script` deploy to a dev Pi, e2e runner,
  screenshot/drive tooling.

[0.3.0]: https://github.com/vitalipe/ujima/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/vitalipe/ujima/releases/tag/v0.2.0
