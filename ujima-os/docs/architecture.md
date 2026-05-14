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

Each protocol is detailed in `ujima.runtime.protocols`.

Target runtimes are low-level implementations and should stay stateless.

`ujima-agent` is the stateful runtime layer. It holds state, applies config, monitors the control token, and discovers peers/content.

`ujima-agent` calls into the selected target runtime through protocols and should stay Linux/CPU/target agnostic where possible.

---

## Namespace Strucure

```

config/ujima.edn ; deployment/runtime environment config 

ujima.runtime.protocols     ; UjimaSystem UjimaDesktop UjimaDiscovery UjimaRuntime protocols
ujima.runtime.target.rpi    ; rpi runtime impl
ujima.runtime.target.mock   ; mock runtime impl
ujima.runtime               ; (->runtime) factory

ujima.agent                 ; holds state/cache and syncs the system with settings
ujima.agent.events          ; callback logic from system events 
ujima.agent.reconcile       ; settings reconciliation
ujima.agent.commands        ; user/admin commands that update settings + reconcile/apply


ujima.http ; HTTP server around the runtime and agent
ujima.cli  ; simple CLI client

ujima.edn ; edn parsing helpers
ujima.io  ; cross platform shell and fs operations
ujima.log ; logger 

```