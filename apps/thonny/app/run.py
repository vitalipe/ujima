"""Thonny, UjimaOS-flavoured — tweaks applied at the entry point so the dpkg-owned
package stays pristine (upstream has no config switch for either):
  - THONNY_MODE=simple pins the beginner UI even if a stray config says regular
    (workbench.py reads the env var before the option — upstream mechanism, drift-proof)
  - the Support-Ukraine toolbar button and the sticky "switch to regular mode" link
    are dropped, FAIL-OPEN: if thonny's internals move, launch unpatched (they
    reappear; the mode stays pinned by the env var) rather than not at all."""
import os
import sys

os.environ["THONNY_MODE"] = "simple"

try:
    from thonny import workbench

    _add_command = workbench.Workbench.add_command

    def add_command(self, command_id, *args, **kwargs):
        if command_id == "SupportUkraine":
            return None
        return _add_command(self, command_id, *args, **kwargs)

    workbench.Workbench.add_command = add_command
    workbench.Workbench._init_regular_mode_link = lambda self: None
except Exception as e:
    print("thonny run.py: patch failed, launching unpatched:", e, file=sys.stderr)

from thonny import launch

sys.exit(launch())
