# Changelog

All notable changes to Ujima, newest first.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
A release's version is its **git tag** (`vX.Y.Z`) — tags are the only source of
version truth; branch names and build labels may disagree.

## [Unreleased]

### Added

- The clock never boots backwards: a heartbeat records witnessed time on the
  settings partition and boot lifts a lagging clock to it — wall time stays
  monotonic across power loss on battery-less boards.
- `hwclock` ships (`util-linux-extra`): a set clock persists to the Pi's RTC,
  surviving reboots while the board has power.
- Every machine mints a persistent identity at first boot: a generated system id
  on the settings partition, surviving A/B installs and board swaps; the machine
  API reports it as `id` (null until a first boot stamps it).
- The machine API reports the deployed version: `query/machine/image` answers
  the stamp written at build/deploy (git tag, plus commit id past a tag,
  `-dirty` for uncommitted trees); images from before the stamp answer null.
- The device API on :1337 can answer in edn: `?format=edn` on any data route
  (JSON stays the default; files and streams are unaffected).
- The image bakes ujima's bb libraries into `/ujima/m2` (pinned, sha-verified
  at build); like packages, they never change on a live deploy.

- Ujima Circle App (dev-only, not wired yet): a fleet control panel for teachers —
  runs against a file-backed mock fleet.
- Ujima Setup App (dev-only, not wired yet): per-machine setup over the same
  fleet — name, clock (timezone + wall time), keyboard layouts, sound output,
  diagnostics with derived checks, remove/rescan.
- One console backend serves both panels: `bb console mock` on :1338 (`/` picks
  a panel, `/circle/`, `/setup/`), `bb console world` is the TUI driving the
  shared mock world (replaces `bb circle mock|world`).
- The OS image boots as-is — `dd` it to a card for a system without A/B, where
  settings and storage are not persistent.
- A usb stick carrying an admin token opens the Console: removable partitions are
  mounted read-only under `/ujima/run/storage/<uuid>` and scanned for it, and pulling
  the stick closes the Console after a few seconds.

### Changed

- The in-session daemon is `/usr/local/bin/ujimad` (was `ujima-agent`).
- The USB admin token is the file `.ujima-admin-token` (was
  `.ujima-control-token`).
- Everything on-device lives under one root — `/ujima`: code at
  `/ujima/ujimad`, desktop at `/ujima/desktop`, apps at `/ujima/apps` (was
  `/opt/ujima`); the desktop helpers and `/usr/games` ride the session `PATH`.
- The storage partition mounts directly at `/ujima/storage` (was
  `/mnt/storage`): the Files area is `/ujima/storage/files`, extra apps
  `/ujima/storage/apps`.
- An installed system records itself in one file, `/ujima/system/pack.edn` (was
  `metadata.edn` + `install.edn`): pack version, packed-at, installed-at.
- The device API on :1337 has one shape: reads under `/api/query/`, writes under
  `/api/commands/`. The old per-subject paths are gone.
- The desktop's own surface — its streams, verbs, app catalog and files — now
  answers on `127.0.0.1:1336` under `/ujima-desktop/`, and is no longer
  reachable from the LAN; `:1337` serves `/api` only. Per-app icons moved from
  `GET /app/icon/<id>` to `GET /ujima-desktop/assets/app-icon/<id>`.
- The device API answers commands and settings reads only to a signed request,
  and signs every response; machine facts under `/api/query/machine/` stay open.

### Fixed

- Renaming the machine also updates `/etc/hosts`, so `sudo` no longer warns
  "unable to resolve host" after a rename.

## [0.3.0] - 2026-07-28

First app support: an app is a self-contained directory (`app.edn` + icon +
payload) discovered by a boot-time catalog scan.

### Added

- App kinds — `app.edn` declares `:kind` (`:exec` / `:web-app` / `:link`);
  apps launch with their app dir as cwd.
- Persistent Files — the file manager and every save/open dialog center on
  `/mnt/storage/files` (survives reboots and OS updates); home is "Temporary".
- Multi-root catalog — `/mnt/storage/apps` is scanned alongside the baked
  `/opt/ujima/apps`; an app dropped onto storage joins the launcher.
- Per-app icons — the app dir owns `icon.svg`, served at `GET /app/icon/<id>`.
- Web-app runtime scripts — `ujima-open-web-app` (one chromium kiosk wrapper
  for all web apps) and `ujima-serve-web-app` (serve a vendored SPA, open it,
  reap the server on close).
- App scopes run under no-new-privs — `sudo` can't elevate from inside an app.
- First-run app defaults — Thonny, Geany and GIMP dark (GIMP defers to the
  system Nordic theme); GIMP, Inkscape and LibreOffice first-run
  popups/infobars off.
- Keyboard chords — Alt+F4 closes (double-press force-kills), Alt+Tab /
  Alt+Shift+Tab cycle running apps, Alt+Escape goes home leaving the app
  running.

### Changed

- The catalog is built at boot by scanning per-app `app.edn` files
  (`assets/apps/<id>/` baked to `/opt/ujima/apps/<id>/`); the app id is the
  directory name; a broken app dir is skipped and logged, never killing the
  session.
- Excalidraw launches through `ujima-serve-web-app`.
- The Web browser runs in guest mode — each browsing session is ephemeral, with
  no history or cookies kept; downloads still land in Files.

### Removed

- The generated central `apps.edn` — per-app `app.edn` is the runtime truth;
  `appcatalog.clj` keeps only the install recipes.
- Excalidraw's bespoke `run.sh`.

### Fixed

- `systemctl restart ujima` tears down the whole session, wedged ujimad included,
  and the next start reclaims display `:0` — no orphaned desktop serving old code.

## [0.2.0] - 2026-07-14

The Ujima desktop shell — first tagged release: Raspberry Pi 5 image build,
read-only root, a babashka settings daemon (ujimad), an i3 + eww + WebKitGTK shell, and a
~20-app catalog.

- **Image build** — `bb tools` builds the flashable Pi image end-to-end: Debian
  trixie rootfs, staged install scripts, pinned packages, per-slot settings and
  shared storage under `/ujima`.
- **Read-only OS** — `/` locked via an overlayroot tmpfs overlay (baked
  initramfs, pinned machine-id); journald persists to storage, capped and
  priority-tagged.
- **Settings daemon (ujimad)** — desired-vs-actual converge over path-vector setting
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

[Unreleased]: https://github.com/vitalipe/ujima/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/vitalipe/ujima/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/vitalipe/ujima/releases/tag/v0.2.0
