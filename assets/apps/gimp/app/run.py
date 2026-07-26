"""GIMP, UjimaOS-flavoured: seed a first-run sessionrc so the file dialogs open
filling the band between the bars — GIMP restores dialog geometry itself, and its
default lands them under the top bar. Band = the live output minus the bar heights,
measured at launch. FAIL-OPEN: any hiccup -> launch unseeded."""
import json
import os
import subprocess
import sys

TOP, DOCK = 48, 68  # eww bar heights — keep in sync with i3 config gaps

rc = os.path.expanduser("~/.config/GIMP/3.0/sessionrc")
if not os.path.exists(rc):
    try:
        outputs = json.loads(subprocess.check_output(["i3-msg", "-t", "get_outputs"]))
        rect = next(o["rect"] for o in outputs if o.get("active"))
        entry = ('(session-info "toplevel"\n'
                 '    (factory-entry "gimp-file-%s-dialog")\n'
                 '    (position 0 %d)\n'
                 '    (size %d %d))\n')
        os.makedirs(os.path.dirname(rc), exist_ok=True)
        with open(rc, "w") as f:
            for dialog in ("open", "save", "export"):
                f.write(entry % (dialog, TOP, rect["width"], rect["height"] - TOP - DOCK))
    except Exception as e:
        print("gimp run.py: sessionrc seed failed, launching unseeded:", e, file=sys.stderr)

os.execvp("gimp", ["gimp"])
