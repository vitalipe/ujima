#!/bin/sh
# UJIMA_CIRCLE_TOKEN arrives in the environment, never in argv.
set -eu

PORT=1338
# the console tree ships with the desktop layer (its whole dir is mirrored to /ujima/desktop)
ROOT=/ujima/desktop/console

[ -d "$ROOT/src" ] || { echo "console not installed at $ROOT" >&2; exit 1; }

# from $ROOT: the console resolves ui/ and dev/ relative to its own dir
cd "$ROOT"
bb -cp "src:/ujima/runtime/src" -m console.main &

until curl -sf "http://127.0.0.1:$PORT/" >/dev/null 2>&1; do sleep 0.2; done

exec ujima-open-web-app "http://127.0.0.1:$PORT/" ujima-console
