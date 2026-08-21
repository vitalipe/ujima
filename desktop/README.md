# desktop/

Product source of the UjimaOS desktop, mirrored wholesale to `/ujima/desktop` by the
desktop stage (`bb dev script <ip> desktop` iterates it live).

- `shell/` — the chrome: i3 config, eww bars, the launcher home surface, icons, wallpaper.
- `bin/` — the desktop's programs (url handler, web-app wrappers, …); on the session PATH.
- `console/` — the chooser and the one backend behind both panels (`src/console/`),
  with the panel apps themselves at `ui/circle` and `ui/setup`.
