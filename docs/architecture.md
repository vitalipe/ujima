# Architecture

## Goal

This repo contains the low-level UjimaOS runtime layer and the local agent.

The design goal for v1 is to keep moving parts minimal:

- one stateful service: `ujima-agent`
- stateless low-level target runtimes
- protocol-based target support

UjimaOS should avoid relying on manual Linux configuration. Persistent intent should come from Ujima config/admin flows, and runtime state should usually be queried from the actual runtime environment.

---

## Top Level

The repo is organized around:

- `ujima-agent`
- `ujima-runtime`
- `ujima-deploy`
- HTTP server round `ujima-agent` and `ujima-runtime`
- CLI wrapper round `ujima-runtime`

Example target runtimes:

- `rpi`
- `x86`
- `browser`
- `mock`

A target runtime implements the following protocols:

- `UjimaSystem`
- `UjimaDesktop`
- `UjimaDiscovery`
- `UjimaRuntime`
- `UjimaTryBoot`

Each protocol is detailed in `ujima.runtime.protocol`.

Target runtimes are low-level implementations and should stay as stateless as possible.

`ujima-agent` is the stateful runtime layer. It holds state, applies config, monitors the control token, and discovers peers/content.

`ujima-agent` calls into the selected target runtime through protocols and should stay Linux/CPU/target agnostic where possible.

---

## Namespace Strucure

```

config/ujima.edn ; deployment/runtime environment config 

ujima.runtime.protocol     ; UjimaSystem UjimaDesktop UjimaDiscovery UjimaRuntime  protocols
ujima.runtime.settings     ; user/admin commands that update settings + settings reconcile logic

ujima.deploy.protocol     ; UjimaDeployTarget
ujima.deploy.pack         ; Ujima pack format, cross target operations


ujima.agent                 ; holds state/cache and syncs the system with settings
ujima.agent.events          ; callback logic from system events 


ujima.target.rpi    ; rpi runtime+deploy impl
ujima.target.mock   ; mock runtime+deploy impl


ujima.http ; HTTP server around the runtime
ujima.cli  ; simple CLI client

ujima.edn ; edn parsing helpers
ujima.io  ; cross target shell and fs operations
ujima.log ; logger 

```