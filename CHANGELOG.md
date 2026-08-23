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

- Solo mode: pin the machine to one app filling the screen, with no switching
  or closing and automatic relaunch if it exits — `app/open {:mode :solo}` to
  enter, `app/unsolo` to leave, and inserting the circle token releases it.
  The app's own dialogs (Open/Save) stay usable.
  The Circle app opens an app in solo with a "keep to this app" checkbox, and
  Close app releases a soloed machine.
- Stellarium opens windowed instead of taking the whole screen with no way
  out — the top bar's close button is always reachable now.
bindsym Mod1+Escape    exec --no-startup-id ujima-desktop POST commands/app/home >/dev/null
- Ujima Circle App: a fleet control panel for teachers — every machine in the
  circle, what each one is running, and sound, apps and power over a selection.
- Ujima Setup App: per-machine setup over the same circle — name, clock (timezone
  + wall time), keyboard layouts and sound output, plus a machine's identity,
  address, uptime, storage and warnings.
- The Console finds its circle by sweeping the local subnet: a machine that answers
  and holds the same admin token joins the list, one that stops answering stays on
  it marked off, and Rescan sweeps again. Machines on the network that belong to
  another circle are counted, never listed.
- The OS image boots as-is — `dd` it to a card for a system without A/B, where
  settings and storage are not persistent.
- A usb stick carrying an admin token opens the Console: removable partitions are
  mounted read-only under `/ujima/run/storage/<uuid>` and scanned for it, the token has
  to be this machine's own circle key or the stick is ignored, the Console pins beside
  Files in the dock while the token is in — closing its window leaves the icon, a tap
  reopens it — and pulling the stick closes the Console after a few seconds.
- Desktop windows fade in on open and out on close (~130ms), as do the shell's
  popovers and the bars hiding for a fullscreen app.
- Wifi is a setting: `[:network :wifi :essid]` (default `ujima-default-circle`) + `:psk`
  (nil = open network) name the network to join and `:mode` (`:peer` | `:off`) drives the
  radio — circle-wide, with a per-device override; seeded at install like any setting,
  re-asserted at every boot.
- A wired link with no DHCP answer falls back to a zeroconf (169.254/16) address, so a
  circle on a bare switch stays addressable.

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
- Wifi power saving is off on every connection: dozing turned ~10ms LAN round
  trips into 40-100ms medians with seconds-scale outliers, for at most ~0.2W on
  mains-powered machines.
- Setting a machine's clock can carry its timezone, so one call moves both; a
  zone the machine does not know is refused and the clock is left untouched.

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
