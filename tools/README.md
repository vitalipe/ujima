# tools/

The host-side CLI behind the `bb` tasks (`tools.cli` dispatch → `cmd/*`).

- `cmd/build`, `cmd/stage` — the pipeline: vendor base → staged image → pack → A/B disk.
- `cmd/pack`, `cmd/disk`, `cmd/loopback` — artifacts and disks.
- `cmd/dev` — the live loop against a dev Pi: push, script, view, screenshot, synthetic input.

Image-script execution itself lives in `os/build` (shared with the dev loop); this
tree is dispatch + host wiring.
