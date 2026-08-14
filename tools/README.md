# tools/

The host-side CLI behind the `bb` tasks (`tools.cli` dispatch → `cmd/*`).

One `cmd/<verb>` namespace per `bb` task.

Image-script execution itself lives in `os/build` (shared with the dev loop); this
tree is dispatch + host wiring.
