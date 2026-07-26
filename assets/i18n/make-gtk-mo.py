"""Generate gtk30-ujima.mo — the GTK chooser label override ("Home" -> "Temporary").

GTK hardcodes the Home entry in every file chooser's sidebar; gettext is the only
config-free lever over its label. Home IS the tmpfs overlay upper on the image, so the
label states the truth: it does not survive a reboot (Files, on storage, does).

The staged .mo REPLACES the distro's en_GB/en_US gtk3 catalogs (tools.scripts.desktop) —
every other GTK string falls back to its American-English msgid, which is acceptable.

Regenerate after editing CATALOG:  python3 make-gtk-mo.py gtk30-ujima.mo
"""
import struct
import sys

CATALOG = {
    "": "Content-Type: text/plain; charset=UTF-8\n",
    "Home": "Temporary",
}

keys = sorted(CATALOG.keys())
ids = b""
strs = b""
offsets = []
for k in keys:
    kb = k.encode("utf-8")
    vb = CATALOG[k].encode("utf-8")
    offsets.append((len(ids), len(kb), len(strs), len(vb)))
    ids += kb + b"\x00"
    strs += vb + b"\x00"

n = len(keys)
keystart = 7 * 4 + 16 * n
valuestart = keystart + len(ids)

out = struct.pack("Iiiiiii", 0x950412DE, 0, n, 7 * 4, 7 * 4 + n * 8, 0, 0)
for o, l, _, _ in offsets:
    out += struct.pack("ii", l, o + keystart)
for _, _, o, l in offsets:
    out += struct.pack("ii", l, o + valuestart)
out += ids + strs

with open(sys.argv[1], "wb") as f:
    f.write(out)
print("wrote", sys.argv[1], len(out), "bytes")
