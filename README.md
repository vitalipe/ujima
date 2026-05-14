# Ujima

UjimaOS is a local-first operating environment for shared public computers.

It is designed for places like classrooms, libraries, community centers, labs, shelters, and offline-first learning spaces where computers are used by many people and maintained by only a few technical users.

UjimaOS is built around a peer-to-peer model. Each machine can run the local Ujima service, discover nearby Ujima machines on the same network, and participate in a local “Ujima Circle” without requiring a central cloud server.

The goal is to make a group of shared computers feel like one manageable local system.

Core ideas:

- **Local-first operation** — devices should keep working even without internet access.
- **Peer-to-peer coordination** — machines discover and communicate with each other on the local network.
- **Zero-config networking** — nearby Ujima machines should be discoverable without manual IP setup.
- **Remote admin without a central server** — an authorized local admin can control machines through the local web interface.
- **Shared-computer management** — designed for public or semi-public computers used by many people.
- **A/B system updates** — support safer system updates with rollback-friendly image/version switching.
- **Low-maintenance deployment** — reduce the need for manual Linux administration on each machine.
- **Offline content support** — allow local content discovery and access inside the Ujima network.

This mono repo contains the code and tooling needed to build, install, and run UjimaOS.