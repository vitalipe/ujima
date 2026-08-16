# runtime/

The ujima core codebase — shared, not the daemon's private tree.

- `src/lib/` — shared infrastructure (shell DSL, io, http edge, tasks, cli): the
  project's standard library; everything else builds on it.
- `src/schema/` — the data plane: settings vocabulary + pinned catalogs, pure
  data (see its README).
- `src/ujima/ujimad.clj` — the daemon entry point.
- `src/ujima/api.clj` — the `/api` tier of the machine edge.
- `src/ujima/linux/` — linux glue.
- `src/ujima/device/` — device + A/B install.
- `src/ujima/control/` — the control plane.
- `src/ujima/desktop/` — the desktop/session layer.
- `config/` — deployment config (`ujimad.edn`), staged with the code.
- `test/` — the unit + integration suites (`bb test:unit`, `bb test:integration`).

The ujimad stage deploys this tree to `/ujima/ujimad`; on-device consumers beyond the
daemon (the installer) run from the same deploy.

ujimad is session-scoped, not a system daemon: i3 execs it inside the X session, so
app/window lifecycle and both HTTP tiers (`/api` on `:1337`, the desktop's own surface
at `/ujima-desktop` on loopback `:1336`) run with the session's environment.
`ujima.service` runs and supervises the whole session — its `Restart=always` *is* the
recovery story (the unit file carries the full rationale).
