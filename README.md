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
- **`desktop/`** — the desktop product: the shell, its programs, and the panel apps.
- **`os/`** — the system definition: `pipeline/` (the build stages, each with the static files it stages), `build/` (the machinery that runs them), `apps/` (third-party software packaged for ujima).
- **`tools/`** — the host CLI behind the `bb` tasks: pipeline orchestration, image/pack/disk work, and the dev loops — a live device over ssh, a simulated circle on the LAN.


## CLI

```
bb build rpi pack [<out.pack>] [--dev]                          build the artifact: stage -> os apply -> pack
bb build rpi disk <img|blockdev> [settings.edn] [--dev] [--wipe] the same, then provision: layout -> slot A -> seed -> activate

bb stage <target>                                     stage an OS image from the pinned base (vendor-cached)

bb os apply <img> [--dev]                             run the whole script chain into a staged image
bb os script <img> <name>                             run pipeline script <name> inside the image's chroot
bb os chroot <img>                                    interactive root shell inside the image

bb pack <img|blockdev> <out.pack>                     pack an OS image into a .pack
bb pack validate <pack>
bb pack meta <pack> [--format edn|json]

bb disk autoboot empty <img|blockdev> [--wipe]                             write an empty A/B layout
bb disk autoboot from-pack <pack> <img|blockdev> [settings.edn] [--wipe]   the installer: layout -> slot A -> seed -> activate
bb disk autoboot slot <A|B> from-pack <pack> <img|blockdev> [settings.edn] install a pack into a slot
bb disk autoboot slot <A|B> from-image <img> <img|blockdev> [settings.edn] install an OS image into a slot (packs to a temp file)
bb disk autoboot slot <A|B> activate <img|blockdev>                        set the boot slot
bb disk info <img|blockdev>                                                slots, install metadata, boot selection

bb dev push <ip> runtime                               deploy the daemon + restart the session
bb dev script <ip> <name>                             run pipeline script <name> live on the device
bb dev view <ip>                                      interactive VNC mirror of the device's display
bb dev screenshot <ip>                                one-frame PNG of the device's display
bb dev click <ip> <x> <y>                             synthetic input on the device
bb dev type <ip> <text>
bb dev key <ip> <chord>

bb circle sim up --range <a.b.c.x-y>                  fake a circle of machines on real LAN addresses (needs root)
bb circle sim down                                    stop the sim and release its addresses
bb circle console up <self-ip>                        the Console panels on :1338, sweeping that machine's subnet

bb loopback attach <img> [--readonly]                 loop-device utility
bb loopback detach <img|loopdev>
bb loopback list

bb pin <schema|deps|initramfs>                        pull world-truth into the repo as a committed pin

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
sudo bb build rpi pack          # release artifact
sudo bb build rpi pack --dev    # dev artifact (`bb dev` commands require it)
```

Dev images are wide open by design — ssh with the default `ujima/ujima` login and
the VNC/input relay baked in. release images ship none of it.

The first build fetches the pinned raspios base once into `out/cache/` and
bakes the packages into it (the install stage).  **no command ever deletes it** — rebuilding it is a manual `rm out/cache/<name>.img`. Every later build starts from the cache:
copy → content scripts in the chroot → pack.

Outputs land in `out/` as `ujima-<branch>-<commit>[-dirty][-dev].*`:

```
….img         the OS image (2 partitions — what the chroot scripts mutate)
….pack        the shipping/install artifact (raw partitions + metadata, zstd)
```

`build rpi disk` runs that same pipeline and then provisions the target: A/B
layout, the freshly built image installed into slot A, slot A activated. The
target is an SD card (≥ 32 GB; the storage partition fills whatever the card
offers) or a loopback .img for dev:

```
sudo bb build rpi disk /dev/mmcblk0 --wipe    # a bootable card, one command
sudo bb build rpi disk out/u-disk.img         # the same onto a sparse 28 GiB disk image
sudo bb disk autoboot from-pack out/ujima-….pack /dev/mmcblk0 --wipe   # skip the build, use an existing pack
```

The granular verbs compose to the same result when you need only part of the
pipeline.

### Build an OS image

```
sudo bb stage rpi                              # vendor (cached) -> out/ujima-<branch>-<sha>.img
sudo bb os apply out/ujima-….img --dev         # the script chain; or one at a time: bb os script <img> base
```

The image boots on its own — `dd` it to a card for a system with no A/B and no
settings/storage partitions, so those paths land in the overlay's tmpfs and reset
every boot.

### Create an empty disk

```
sudo bb disk autoboot empty out/u-disk.img   # or a real device: /dev/mmcblk0 (--wipe if used)
```

An empty A/B harness: control, boot A/B, root A/B, settings, storage.

### Apply to a slot

```
sudo bb pack out/ujima-….img out/u.pack                  # the distributable
sudo bb disk autoboot slot A from-pack out/u.pack out/u-disk.img
sudo bb disk autoboot slot A activate out/u-disk.img
sudo bb disk info out/u-disk.img                           # what's in each slot
```

Install re-points only `root=` in the pack's cmdline; everything else on that line
is the image's. `bb disk slot A from-image <img> <disk>` skips the pack — **dev
only**, shipped installs go through a pack.

For the live iteration loop against a running dev device, `bb dev push <ip> runtime`
deploys the runtime and restarts the session; `bb dev script <ip> <name>` runs any
OS script in place. Live deploys ship **code and static files only** — they never
install packages, so anything that needs a new binary is an image rebuild (the
failure mode is a quiet "won't open": the spawn throws on the missing binary).


## Disk layout

**The A/B harness** — the card layout, owned by the installer: a control partition,
boot + root **slot pairs**, and two partitions that belong to no slot — **settings**
and **storage**. An update installs a pack into the inactive slot and activates it;
the Pi's tryboot mechanism falls back to the previous slot if the new one fails to
boot. Each install writes that slot's fstab, so a slot always mounts its own boot
partition and the shared pair: the settings partition lands at `/mnt/settings`
(one dir per slot), and binding the slot's dir to `/ujima/settings` IS slot
selection.

The autoboot disk — the layout `bb disk autoboot` writes and requires:
```
p1  control    64 MiB  fat32  UJCTL    autoboot.txt — boot slot selection, tryboot
p2  boot A    512 MiB  fat32           slot A kernel + firmware
p3  boot B    512 MiB  fat32           slot B kernel + firmware
p4  extended                           container for the logical partitions
p5  root A     10 GiB  ext4            slot A rootfs
p6  root B     10 GiB  ext4            slot B rootfs
p7  settings    1 GiB  ext4   UJCFG    one dir per slot            (persists)
p8  storage     rest   ext4   UJSTORE  files, extra apps, journal  (persists)
```

The MBR disk id is fixed (`00c0ffee`), so every partition has a stable
`PARTUUID=00c0ffee-0N`; fstab and the kernel's `root=` address partitions by it,
never by device path.

**UjimaOS** — inside a slot, the root filesystem is **read-only** under a tmpfs
overlay: everything written to `/` resets on reboot. What persists — across reboots
*and* A/B updates — lives on the harness's shared partitions: settings, storage, and
the journal. On a running device:

```
/ujima/runtime     the deployed core (runtime/)
/ujima/m2          the bb libraries it runs on
/ujima/desktop     the desktop layer (desktop/, mirrored)
/ujima/apps        the app catalog scan root
/ujima/image.edn   the build stamp (version, base)  (per-slot)
/ujima/install.edn the install record (manifest)    (per-slot)
/ujima/settings    per-slot settings scope          (persists)
/ujima/storage     shared storage — files, apps     (persists)
/ujima/run         ephemeral runtime state          (resets)
/ujima/dev         the dev kit                      (dev images only)
```


## Development

### A running device

A `--dev` image ships the dev kit, so a device on the network can be watched and
driven from here without reflashing it: mirror its screen, pull a single frame,
send synthetic input, or deploy the daemon and restart its session in place.

```
bb dev view <ip>                              # interactive VNC mirror of the screen
bb dev screenshot <ip>                        # one frame -> tmp/screen/ujima-screen.png
bb dev click <ip> 640 400                     # synthetic input on :0
bb dev push <ip> runtime                       # deploy the daemon, restart the session
```

### Circle

Two terminals give you a circle sim and a Console driving it. The sim claims real addresses on
the LAN and answers on them exactly as a machine does, so the Console finds them by
sweeping — its argument is the machine it administers, and that machine's subnet is
the one swept, so pointing it at a real device instead makes the fakes that device's
circle. Both default `--token` to the baked circle key, and the sim needs root to
claim addresses; ctrl-c releases them, as does `bb circle sim down` from anywhere.

```
bb circle sim up --range 192.168.1.200-229    # 30 fake machines
bb circle console up 192.168.1.200            # the panels on :1338, sweeping that /24
```

### Pins

World-truth the build cannot resolve for itself is committed as a pin, and the
consumer verifies it. `deps` is re-pinned when the bb dependency set changes — a
library added or bumped; `schema` and `initramfs` only when the base image does,
capturing its tz/xkb catalogs and its kernel-matched initramfs. Two have to come
from somewhere other than this host — schema from a mounted image rootfs,
initramfs from a dev Pi with its overlay off.

```
bb pin deps                                   # deps.edn -> os/build/deps-pin.edn
bb pin schema <rootfs>                        # tz/xkb catalogs from a mounted image rootfs
bb pin initramfs <ip>                         # on a dev Pi, overlay off first
```
