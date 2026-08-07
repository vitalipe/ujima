# UjimaOS

UjimaOS is a local-first operating environment for shared public computers.

It is designed for places like classrooms, libraries, community centers, labs, shelters, and offline-first learning spaces where computers are used by many people and maintained by only a few technical users.

UjimaOS is built around a peer-to-peer model. Each machine can run the local Ujima service, discover nearby Ujima machines on the same network, and participate in a local “Ujima Circle” without requiring a central cloud server.

The goal is to make a group of shared computers feel like one manageable local system.

Core ideas:

- **Local-first operation** — devices should keep working even without internet access.
- **Peer-to-peer coordination** — machines discover and communicate with each other on the local network.
- **Zero-config networking** — nearby Ujima machines should be discoverable without manual IP setup.
- **Remote admin without a central server** — an authorized local admin can control machines through a local web interface.
- **Shared-computer management** — designed for public or semi-public computers used by many people.
- **A/B system updates** — support safer system updates with rollback-friendly image/version switching.
- **Low-maintenance deployment** — reduce the need for manual Linux administration on each machine.


This mono repo contains the code and tooling needed to build, install, and run UjimaOS.


## Layout

The split is by type of work — product source, system definition, host tooling:

- **`runtime/`** — the ujima core codebase: shared `lib/` + the `ujima/` runtime. Runs on the device as ujimad; tools and the os build link it on the host.
- **`desktop/`** — the desktop product: `shell/` (the chrome), `bin/` (its programs), `circle/` (the fleet panel).
- **`os/`** — the system definition: `pipeline/` (the build stages, each with the static files it stages), `build/` (the machinery that runs them), `apps/` (third-party software packaged for ujima).
- **`tools/`** — the host CLI behind the `bb` tasks: pipeline orchestration, image/pack/disk work, the live dev loop.


## CLI

```
bb build <target> [--dev]                             the whole pipeline, one command

bb stage <target>                                     stage an OS image from the pinned base (vendor-cached)

bb os apply <img> [--dev]                             run the whole script chain into a staged image
bb os script <img> <name>                             run pipeline script <name> inside the image's chroot
bb os chroot <img>                                    interactive root shell inside the image

bb pack <img|blockdev> <out.pack>                     pack an OS image into a .pack
bb pack validate <pack>
bb pack meta <pack> [--format edn|json]

bb disk ab create <scheme> <img|blockdev>             write an empty A/B layout (scheme: autoboot)
bb disk slot <A|B> from-pack <pack> <img|blockdev>    install a pack into a slot
bb disk slot <A|B> from-image <img> <img|blockdev>    install an OS image into a slot (packs to a temp file)
bb disk slot <A|B> activate <img|blockdev>            set the boot slot
bb disk info <img|blockdev>                           slots, install metadata, boot selection

bb dev push <ip> ujimad                               deploy the daemon + restart the session
bb dev script <ip> <name>                             run pipeline script <name> live on the device
bb dev view <ip>                                      interactive VNC mirror of the device's display
bb dev screenshot <ip>                                one-frame PNG of the device's display
bb dev click <ip> <x> <y>                             synthetic input on the device
bb dev type <ip> <text>
bb dev key <ip> <chord>

bb loopback attach <img> [--readonly]                 loop-device utility
bb loopback detach <img|loopdev>
bb loopback list

bb repl                                               dev REPL
bb test:unit                                          unit tests
bb test:integration <test|all>                        integration suite (loopback, needs root)
```

The same OS script runs in the build chroot (`bb os script x.img desktop`) and on a
live device (`bb dev script <ip> desktop`).


## Building

The target hardware is the Raspberry Pi 5 (including the Pi 500).

Host requirements: Linux, [babashka](https://babashka.org), root for
loopback/chroot work, `binfmt_misc` with qemu-aarch64 registered (e.g.
`apt install qemu-user-static`), `zstd`, and ~25 GB free disk. The dev loop
additionally wants `sshpass` and `rsync`.

```
sudo bb build rpi-os          # release image
sudo bb build rpi-os --dev    # dev image (`bb dev` commands require it)
```

Dev images are wide open by design — ssh with the default `ujima/ujima` login and
the VNC/input relay baked in. That is the public-access threat model (physical
access already implies root); release images ship none of it.

The first build fetches the pinned raspios base once into `out/cache/` and
bakes the packages into it (the install stage).  **no command ever deletes it** — rebuilding it is a manual `rm out/cache/<name>.img`. Every later build starts from the cache:
copy → content scripts in the chroot → pack → A/B disk.

Outputs land in `out/` as `ujima-<branch>-<commit>[-dirty][-dev].*`:

```
….img         the OS image (2 partitions — what the chroot scripts mutate)
….pack        the shipping/install artifact (raw partitions + metadata, zstd)
…-disk.img    the flashable A/B disk (slot A installed and activated)
```

Flash the full build's A/B harness disk to an SD card (28 GiB — use a ≥32 GB card):

```
sudo dd if=out/ujima-…-disk.img of=/dev/mmcblk0 bs=4M conv=fsync status=progress
```

The granular verbs compose to the same result when you need only part of the
pipeline.

### Build an OS image

```
sudo bb stage rpi-os                           # vendor (cached) -> out/ujima-<branch>-<sha>.img
sudo bb os apply out/ujima-….img --dev         # the script chain; or one at a time: bb os script <img> base
```

The image boots on its own — `dd` it to a card for a system with no A/B and no
settings/storage partitions, so those paths land in the overlay's tmpfs and reset
every boot.

### Create a disk

```
sudo bb disk ab create autoboot out/u-disk.img   # or a real device: /dev/mmcblk0
```

An empty A/B harness: control, boot A/B, root A/B, settings, storage.

### Apply to a slot

```
sudo bb pack out/ujima-….img out/u.pack                  # the distributable
sudo bb disk slot A from-pack out/u.pack out/u-disk.img
sudo bb disk slot A activate out/u-disk.img
sudo bb disk info out/u-disk.img                           # what's in each slot
```

Install re-points only `root=` in the pack's cmdline; everything else on that line
is the image's. `bb disk slot A from-image <img> <disk>` skips the pack — **dev
only**, shipped installs go through a pack.

For the live iteration loop against a running dev device, `bb dev push <ip> ujimad`
deploys the daemon and restarts the session; `bb dev script <ip> <name>` runs any
OS script in place. Live deploys ship **code and static files only** — they never
install packages, so anything that needs a new binary is an image rebuild (the
failure mode is a quiet "won't open": the spawn throws on the missing binary).


## The device

**The A/B harness** — the card layout, owned by the installer: a control partition,
boot + root **slot pairs**, and two partitions that belong to no slot — **settings**
and **storage**. An update installs a pack into the inactive slot and activates it;
the Pi's tryboot mechanism falls back to the previous slot if the new one fails to
boot. Each install writes that slot's fstab, so a slot always mounts its own boot
partition and the shared pair: the settings partition lands at `/mnt/settings`
(one dir per slot), and binding the slot's dir to `/ujima/settings` IS slot
selection.

**UjimaOS** — inside a slot, the root filesystem is **read-only** under a tmpfs
overlay: everything written to `/` resets on reboot. What persists — across reboots
*and* A/B updates — lives on the harness's shared partitions: settings, storage, and
the journal. On a running device:

```
/ujima/ujimad      the deployed core (runtime/)
/ujima/desktop     the desktop layer (desktop/, mirrored)
/ujima/apps        the app catalog scan root
/ujima/system      the install record (pack.edn)    (per-slot)
/ujima/settings    per-slot settings scope          (persists)
/ujima/storage     shared storage — files, apps     (persists)
/ujima/run         ephemeral runtime state          (resets)
/ujima/dev         the dev kit                      (dev images only)
```