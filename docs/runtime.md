# UjimaOS Runtime Prototype

Local-first runtime, agent, HTTP API, and CLI for UjimaOS.

## Goals

- Simple local-first architecture
- Minimal moving parts
- Runtime abstraction per target platform
- Event-driven agent loop

---

# Configuration

Main config file:

```text
config/ujima.edn
```

Example:

```clojure
{:http {:host "0.0.0.0"
        :port 1337}

 :log {:level :info}

 :runtime
 {:target :mock

  :mock
  {:control-token-toggle-ms 5000
   :state
   {:hostname "ujima-mock"
    :timezone "UTC"
    :keyboard-layouts ["us"]
    :wallpaper nil
    :volume 50
    :screen-locked false
    :control-token {:present? true :type :usb}
    :settings {}}}

  :rpi
  {:paths
   {:settings "/var/lib/ujima/settings.edn"}}}}
```

---

# Running

## HTTP Server

```bash
bb http
```

Explicit config:

```bash
bb http config/ujima.edn
```

---

## CLI

Get volume:

```bash
bb cli volume
```

Set volume:

```bash
bb cli volume 60
```

Get hostname:

```bash
bb cli hostname
```

Set hostname:

```bash
bb cli hostname ujima-01
```

---

# HTTP API

All runtime endpoints live under:

```text
/api/runtime
```
---

## Runtime

```text
GET  /api/runtime/settings
POST /api/runtime/settings

GET  /api/runtime/control-token
```
---


## System

```text
GET  /api/runtime/system/hostname
POST /api/runtime/system/hostname

GET  /api/runtime/system/timezone
POST /api/runtime/system/timezone

GET  /api/runtime/system/keyboard-layouts
POST /api/runtime/system/keyboard-layouts
```

---

## Desktop

```text
GET  /api/runtime/desktop/volume
POST /api/runtime/desktop/volume

GET  /api/runtime/desktop/wallpaper
POST /api/runtime/desktop/wallpaper

GET  /api/runtime/desktop/screen-locked
POST /api/runtime/desktop/screen-locked
```

---

# Smoke Tests

## HTTP

Run server:

```bash
bb http
```

Run tests:

```bash
bb test-http
```

---

## CLI

Run tests:

```bash
bb test-cli
```

---


# Control Token

Current behavior:

- watches for USB block events using `udevadm monitor`
- probes mounted media for:

```text
/media/*/*/.ujima-control-token
```

Example token state:

```clojure
{:present? true
 :type :usb
 :file "/media/pi/UJIMA/.ujima-control-token"}
```

---

# Design Notes

- Runtime protocols expose actual system state
- Desired settings are stored separately
- Reconciliation is handled by the agent
- HTTP and CLI are thin wrappers around the runtime
