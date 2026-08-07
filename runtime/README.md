# runtime/

The ujima core codebase — shared, not the daemon's private tree.

- `src/lib/` — shared infrastructure (shell DSL, io, tasks, cli); `tools/` and `os/build` link it on the host.
- `src/ujima/` — the runtime: control plane, desktop/session layer, device + A/B install, linux glue. Entry: `ujima.ujimad`.
- `config/` — deployment config (`ujimad.edn`), staged with the code.
- `test/` — the unit + integration suites (`bb test:unit`, `bb test:integration`).

The ujimad stage deploys this tree to `/ujima/ujimad`; on-device consumers beyond the
daemon (the installer) run from the same deploy.
