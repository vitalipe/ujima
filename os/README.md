# os/

The system definition — how a machine is assembled around the product.

- `pipeline/<stage>/` — the build stages (install → boot → base → ujimad → desktop →
  ujimaify → [dev] → [cleanup]): each a `script.clj` plus the static device files it
  stages, grouped per concern.
- `build/` — the machinery: the script contract (`scripts`), the chroot executor
  (`image`), file staging (`files`), app packaging (`apps`), vendored bb + qemu (`vendor/`).
- `apps/<id>/` — third-party software packaged for ujima: `app.edn` (runtime spec,
  ships to the device) + `install.edn` (build recipe, doesn't) + `rootfs/` first-run
  defaults. Adding an app = one dir.

Run a stage with `bb os script <img> <name>` (chroot) or `bb dev script <ip> <name>`
(live device).
