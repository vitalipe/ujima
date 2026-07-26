#!/bin/sh
# geany's Save dialog follows process cwd (the only catalog app that does) — start in Files
# so saves land on storage. cd is fail-open: with storage absent geany still launches,
# falling back to the app-dir cwd like every other app.
cd /mnt/storage/files 2>/dev/null
exec geany "$@"
