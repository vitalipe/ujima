# Deployment and Upgrade Model

## Installation

A new site is typically deployed by:

1. Connecting hardware
2. Booting from a Ujima installer USB
3. Configuring:
   - Wi-Fi
   - site information
   - circle information
4. Installing UjimaOS locally
5. Repeating the process for additional peers

The installer USB also acts as an administrative token.

## Ujima Packs

A Ujima installation package is currently:

- a metadata file
- a boot partition image
- a root filesystem image

The target implementation installs these artifacts in a way compatible with A/B partitioning and rollback-safe upgrades.

## A/B Updates

UjimaOS uses an A/B-style update model.

During upgrades:

- the new system is installed into the inactive slot
- the existing system remains untouched
- the machine reboots into the new version
- rollback remains possible if the upgrade fails

This design specifically targets environments with:

- unstable power
- unreliable infrastructure
- limited recovery capability
