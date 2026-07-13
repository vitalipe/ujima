#!/bin/sh
# STOPGAP launcher for the vendored Excalidraw static build. python3's http.server exists ONLY to give
# the ES-module app an http:// origin (file:// is CORS-blocked) — it does no logic and lingers across
# launches. dark-default is baked into index.html. TODO: replace the python server with a proper static
# server and fork this into a ujima-native offline whiteboard (LAN collab via self-hosted excalidraw-room).
DIR=/opt/ujima/apps/excalidraw/app
PORT=8090
grep -q ujima-dark-default "$DIR/index.html" 2>/dev/null || \
  sed -i 's|<head>|<head><script id="ujima-dark-default">try{if(!localStorage.getItem("excalidraw-theme"))localStorage.setItem("excalidraw-theme","dark")}catch(e){}</script>|' "$DIR/index.html"
pgrep -f "http.server $PORT" >/dev/null 2>&1 || setsid python3 -m http.server "$PORT" --directory "$DIR" >/dev/null 2>&1 &
until curl -sf -o /dev/null "http://localhost:$PORT/"; do sleep 0.2; done
exec chromium --app="http://localhost:$PORT" --class=ujima-excalidraw \
     --user-data-dir=/tmp/ujima-excalidraw --no-first-run
