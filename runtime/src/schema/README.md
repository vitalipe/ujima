# src/schema/

The data plane — pure data, requires nothing outside this dir. Machinery and
enforcement live elsewhere (control merges scopes; the HTTP gate conforms
values). Split by author:

- `ujima/` — hand-written. `schema.ujima.settings`: one entry per setting —
  key, doc, default, scopes, and the value `:shape` (malli, as data).
- `build/` — machine-written, `build.schema` owns it wholesale (never edit):
  the tz/xkb catalogs, pinned from a real system with `bb pin schema [root]`.

The os build diffs the image's own catalogs against the pins and fails on any
drift — regenerate + commit to accept a package change.
