# Persistence and Security Model

## Persistence Model

UjimaOS currently targets shared guest-session usage.

In v1:

- user sessions are temporary
- session state is stored in RAM
- user changes are discarded on reboot

Persistent system state is intentionally minimal.

Currently persisted settings include:

- wallpaper
- keyboard layouts
- hostname
- screen resolution

Future versions are expected to support:

- USB-backed personal storage
- portable user profiles
- dynamic user environments

## Security and Trust Model

The current security model is intentionally simple.

Administrative access is based primarily on possession of a trusted USB token.

When an authorized installer or administration USB device is inserted:

- the administration interface becomes available
- nearby peers may be managed remotely

The system is designed primarily to prevent accidental misconfiguration rather than defend against highly capable physical attackers.

This reflects the realities of the target deployment environments:

- systems are physically accessible
- SD cards may be removed directly
- operational simplicity is prioritized over enterprise-grade security infrastructure

UjimaOS therefore assumes a mostly trusted local environment.
