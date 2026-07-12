# ujima-admin — remote-control TUI (spike)

A tiny terminal app to drive a UjimaOS device from your laptop over its HTTP API
(the same `/app`, `/api`, `/ui` endpoints the on-device shell uses).

```
bb admin/ujima-admin <ip> [port]      # port defaults to 1337
```

Needs only **babashka** on the laptop (built-in http-client + cheshire — nothing to install).
The device must have its http bound to `0.0.0.0` — see `config/ujimad.edn` → `:desktop :http :host`
(this branch sets it; stock UjimaOS binds loopback only).

## Keys

| key            | action                                             |
|----------------|----------------------------------------------------|
| `↑` / `↓`      | move selection                                     |
| `enter`        | select / drill in                                  |
| `esc`          | back to the main menu                              |
| `q` / `Ctrl-C` | quit                                               |

Inside **Apps**: `enter` open (or switch, if already running) · `c` close focused · `h` home · `r` reconnect
Inside **Volume**: `←`/`→` or `-`/`+` adjust · `m` mute

The header (volume · mute · output · keyboard · running-app count) updates live — the running-app
list rides the `/ui/apps` NDJSON stream, so apps you open/close on the device appear in real time.

## Note

`0.0.0.0` means **anyone on the LAN can control the device** — there is no auth. That's fine for a
trusted-network demo (matches UjimaOS's physical-access threat model); don't expose it beyond that.
