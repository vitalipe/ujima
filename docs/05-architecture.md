# System Architecture

UjimaOS currently runs as a single local service — the **Ujima agent** — built from a small set of
Clojure (babashka) namespaces. It avoids relying on manual Linux configuration: persistent intent
comes from Ujima settings, and live state is read back from the system itself.

> **Status.** This branch focuses on the agent core. The HTTP API, the CLI client, and the
> web-based admin UI are **planned but not built yet** (temporarily removed — see *Deferred*). The
> earlier protocol-based *target/runtime* abstraction has been replaced by the control plane, the
> device module, and direct `ujima.linux.*` calls.

## Layers

### 1. Core & agent loop
`ujima.core` is the entry point (`-main`): it loads config, then initialises logging, the control
plane, and the agent. `ujima.agent` runs the stateful loop — it watches the USB control token
(`ujima.linux.token`), triggers a settings reconcile on start, and reacts to token changes through
`ujima.agent.events`.

### 2. Control plane — settings
`ujima.control.*` owns desired configuration and convergence:
- `ujima.control.defs` — the schema: **scopes** (`:device` persistent, `:session` / `:activity`
  ephemeral) and **settings** (`:system/hostname`, `:system/timezone`, `:keyboard/layout`,
  `:audio/volume`, `:audio/muted`) with defaults and the scopes each may be set in.
- `ujima.control.registry` — pure logic: builds the schema indexes and computes **effective
  settings** by merging scopes (override precedence).
- `ujima.control` — the stateful glue: persists each scope as EDN, applies updates under a lock, and
  exposes `settings` / `update-settings!` / `reconcile!`.
- `ujima.control.reconcile` — **convergence**: drives the OS to match the effective settings,
  delegating every operation to `ujima.linux.*`. Idempotent (applies only when the current value
  differs) and resilient (per-setting failures are logged and skipped).

### 3. Device — low-level device ops
`ujima.device` exposes the device runtime; `ujima.device.ab.*` implements **A/B partitioning and
boot** (`ujima.device.ab.autoboot`, `.partitions`, `.bootfiles`). This is the one place that still
uses protocols — `UjimaSystemDisk` (disk layout / slot install) and `UjimaBootRuntime` (try-boot),
defined in `ujima.device.ab`.

### 4. Linux layer — direct OS operations
`ujima.linux.*` performs low-level operations directly, with no target abstraction:
- `ujima.linux.system` — hostname / timezone / keyboard (`hostnamectl`, `timedatectl`, `localectl`).
- `ujima.linux.desktop` — audio volume / mute (`pactl`).
- `ujima.linux.shell` — the shell layer (`$`, `sudo$`, `sh`, `sudo`) with a **config-driven command
  remap** (`:shell {:commands …}`) — the seam used for mocking/testing (e.g. mapping `hostnamectl`
  → `echo`).
- `ujima.linux.disk[.loop/.mount]` — block-device, loopback, and mount helpers.
- `ujima.linux.token` — USB control-token detection.

### 5. Support
`ujima.pack` (Ujima pack format / install), `ujima.log` (logging). Generic libraries live
under `lib.*`: `lib.config` (config), `lib.io` (file ops), `lib.util` (utilities),
`lib.task[.flow/.timeline]` (async task machinery), `lib.edn`, `lib.cli`.

## Module map

```
config/ujima.edn            ; deployment config

ujima.core                  ; entry point / -main
ujima.agent                 ; stateful agent loop (token watch, reconcile trigger)
ujima.agent.events          ; system-event callbacks

ujima.control               ; settings/control plane (persist, lock, effective settings)
ujima.control.defs          ; scopes + settings schema
ujima.control.registry      ; pure settings derivation + merge
ujima.control.reconcile     ; converge OS to settings (via ujima.linux.*)

ujima.device                ; device runtime entry
ujima.device.ab             ; A/B disk + boot protocols
ujima.device.ab.autoboot(.partitions/.bootfiles)  ; A/B autoboot impl

ujima.linux.system          ; hostname / timezone / keyboard
ujima.linux.desktop         ; audio volume / mute
ujima.linux.shell           ; shell layer + command remap
ujima.linux.disk(.loop/.mount)  ; block device / loopback / mount
ujima.linux.token           ; USB control-token watch

ujima.pack                  ; Ujima pack format / install
ujima.log                   ; logging

lib.config  lib.io          ; config loader / file-io helpers
lib.util                    ; generic utilities
lib.task(.flow/.timeline)   ; async task machinery
lib.edn  lib.cli            ; edn / cli helpers
```

## Configuration

`config/ujima.edn` (deep-merged with `config/config.local.edn`):

```clojure
{:log     {:level :info}
 :shell   {:commands {}}                          ; optional command remap (mock/test seam)
 :control {:storage "/var/lib/ujima/settings"     ; persistent (:device) scope dir
           :tmp     "/run/ujima/settings"}}        ; ephemeral (:session/:activity) scope dir
```

## Control token

The agent watches for a USB **control token** via `ujima.linux.token` (USB block events through
`udevadm`), probing mounted media for `.ujima-control-token`. Token state looks like:

```clojure
{:present? true
 :type     :usb
 :file     "/media/pi/UJIMA/.ujima-control-token"}
```

## Design notes

- Desired configuration is stored as per-scope EDN; effective settings are the merged result.
- Reconciliation is driven by the agent through `ujima.control.reconcile`, which calls into
  `ujima.linux.*`. There is no runtime/target protocol indirection — system state is read and applied
  directly.
- The shell command remap (`:shell {:commands …}`) replaces the old `mock` target for testing.

## Deferred

Planned, but **not in this branch**:
- The HTTP API and the web-based admin UI (the intended primary management surface).
- The CLI client.
- A general protocol-based *target* abstraction (CPU/arch/desktop portability) — currently only the
  device A/B disk/boot layer is protocol-based.
