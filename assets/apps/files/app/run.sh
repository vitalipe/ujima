#!/bin/sh
# The side pane hides via splitter_pos=0 (collapsed to zero width — there is no real
# "no pane" mode; an invalid side_pane_mode just falls back to a visible default), and
# pcmanfm rewrites its conf on every exit — so re-pin before each launch. NOTE pcmanfm is
# single-instance: this only takes effect on a fresh start, not while a window is open.
sed -i -e "s/^side_pane_mode=.*/side_pane_mode=places/" \
       -e "s/^splitter_pos=.*/splitter_pos=0/" \
       /home/ujima/.config/pcmanfm/default/pcmanfm.conf 2>/dev/null
exec pcmanfm /mnt/storage/files
