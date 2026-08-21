# tools/

The host-side CLI behind the `bb` tasks, one noun per task.

Each `cmd/<noun>` ns owns its noun's whole surface as `cli`, and `tools.cli` only
merges them (`os` is the exception — its verbs belong to the image builder).
Nesting is free: a node with a `:target` is a command, anything else groups them.

`cmd/` is dispatch; a noun whose work outgrows that keeps it alongside —
`circle/sim.clj` is the fake fleet, `cmd/circle.clj` only the tree.

Image-script execution itself lives in `os/build` (shared with the dev loop); this
tree is dispatch + host wiring.
