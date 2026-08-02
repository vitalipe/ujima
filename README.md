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


## CLI

```
bb build <target> [--dev]                             the whole pipeline, one command

bb stage <target>                                     stage an OS image from the pinned base (vendor-cached)

bb os apply <img> [--dev]                             run the whole script chain into a staged image
bb os script <img> <name>                             run os.<name> inside the image's chroot
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
bb dev script <ip> <name>                             run os.<name> live on the device
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

The same OS script runs in both harnesses: `bb os script x.img desktop` (build
chroot) and `bb dev script <ip> desktop` (live device).


## Building

Host requirements: Linux, [babashka](https://babashka.org), root for
loopback/chroot work, `binfmt_misc` with qemu-aarch64 registered (e.g.
`apt install qemu-user-static`), `zstd`, and ~25 GB free disk. The dev loop
additionally wants `sshpass` and `rsync`.

```
sudo bb build rpi-os          # release image
sudo bb build rpi-os --dev    # dev image (`bb dev` commands require it)
```

The first build fetches the pinned raspios base once into `stage/vendor/` and
bakes the packages into it (`os.install`).  **no command ever deletes it** — rebuilding it is a manual `rm stage/vendor/<name>.img`. Every later build starts from the cache:
copy → content scripts in the chroot → pack → A/B disk.

Outputs land in `stage/` as `ujima-<branch>-<commit>[-dirty][-dev].*`:

```
….img         the OS image (2 partitions — what the chroot scripts mutate)
….pack        the shipping/install artifact (raw partitions + metadata, zstd)
…-disk.img    the flashable A/B disk (slot A installed and activated)
```

Flash the disk image to an SD card:

```
sudo dd if=stage/ujima-…-disk.img of=/dev/mmcblk0 bs=4M conv=fsync status=progress
```

The granular verbs compose to the same result when you need only part of the
pipeline.

### Build an OS image

```
sudo bb stage rpi-os                             # vendor (cached) -> stage/ujima-<branch>-<sha>.img
sudo bb os apply stage/ujima-….img --dev         # the script chain; or one at a time: bb os script <img> base
```

The image boots on its own — `dd` it to a card for a system with no A/B and no
settings/storage partitions, so those paths land in the overlay's tmpfs and reset
every boot.

### Create a disk

```
sudo bb disk ab create autoboot stage/u-disk.img   # or a real device: /dev/mmcblk0
```

An empty A/B layout: control, boot A/B, root A/B, settings, storage.

### Apply to a slot

```
sudo bb pack stage/ujima-….img stage/u.pack                  # the distributable
sudo bb disk slot A from-pack stage/u.pack stage/u-disk.img
sudo bb disk slot A activate stage/u-disk.img
sudo bb disk info stage/u-disk.img                           # what's in each slot
```

Install re-points only `root=` in the pack's cmdline; everything else on that line
is the image's. `bb disk slot A from-image <img> <disk>` skips the pack — **dev
only**, shipped installs go through a pack.

For the live iteration loop against a running dev device, `bb dev push <ip> ujimad`
deploys the daemon and restarts the session; `bb dev script <ip> <name>` runs any
OS script in place.