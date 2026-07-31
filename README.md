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

bb os stage <target>                                  stage an OS image from the pinned base (vendor-cached)
bb os script <name> <img>                             run os.<name> inside the image's chroot
bb os chroot <img>                                    interactive root shell inside the image
bb os initramfs <img>                                 bake the prebuilt overlayroot initramfs
bb os fetch <url> <out-img> [--sha256 <hex>]          download + decompress a base image

bb pack <img|blockdev> <out.pack>                     pack an OS image into a .pack
bb pack validate <pack>
bb pack meta <pack> [--format edn|json]

bb disk ab create <scheme> <img|blockdev>             write an empty A/B layout (scheme: autoboot)
bb disk slot <A|B> from-pack <pack> <img|blockdev>    install a pack into a slot
bb disk slot <A|B> activate <img|blockdev>            set the boot slot
bb disk info <img|blockdev>                           slots, installed versions, boot selection

bb dev push ujimad <ip>                               deploy the daemon + restart the session
bb dev script <name> <ip>                             run os.<name> live on the device
bb dev view <ip>                                      interactive VNC mirror of the device's display
bb dev screenshot <ip>                                one-frame PNG of the device's display
bb dev click <x> <y> <ip>                             synthetic input on the device
bb dev type <text> <ip>
bb dev key <chord> <ip>

bb loopback attach <img> [--readonly]                 loop-device utility
bb loopback detach <img|loopdev>
bb loopback list

bb repl                                               dev REPL
bb e2e <test|all>                                     e2e suite (loopback, needs root)
bb test:unit                                          unit tests
```

The same OS script runs in both harnesses: `bb os script desktop x.img` (build
chroot) and `bb dev script desktop <ip>` (live device).


## Building

Host requirements: Linux, [babashka](https://babashka.org), root for
loopback/chroot work, `binfmt_misc` with qemu-aarch64 registered (e.g.
`apt install qemu-user-static`), `zstd`, and ~25 GB free disk. The dev loop
additionally wants `sshpass` and `rsync`.

```
sudo bb build rpi-os          # release image
sudo bb build rpi-os --dev    # + ssh/vnc/xdotool dev rig, cleanup skipped
```

The first build fetches the pinned raspios base once into `stage/vendor/` and
bakes the packages into it (`os.install`).  **no command ever deletes it** — rebuilding it is a manual `rm stage/vendor/<name>.img`. Every later build starts from the cache:
copy → prebuilt initramfs → content scripts in the chroot → pack → A/B disk.

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
pipeline:

```
sudo bb os stage rpi-os
sudo bb os script base stage/ujima-….img       # then ujimad, desktop, ujimaify, …
sudo bb pack stage/ujima-….img stage/u.pack
sudo bb disk ab create autoboot stage/u-disk.img
sudo bb disk slot A from-pack stage/u.pack stage/u-disk.img
sudo bb disk slot A activate stage/u-disk.img
```

For the live iteration loop against a running dev device, `bb dev push ujimad
<ip>` deploys the daemon and restarts the session; `bb dev script <name> <ip>`
runs any OS script in place.