# managed by the ujima image build — source: os/pipeline/dev/prompt/prompt.sh (overwritten each run)
#
# Bash prompt for the dev image. The stock `ujima@<host>` prompt is easy to confuse with
# other machines once you've SSH'd into a few boxes; a bold-yellow [ujima-dev] tag makes
# it clear which one you're on. It also tags the overlay state — green [ro] (read-only
# overlay on) vs bold-red [rw] (overlay disabled via lock-fs, writable root) — decided
# once at shell startup, since the state only changes across a reboot.
if [ "$(findmnt -no FSTYPE / 2>/dev/null)" = overlay ]; then
    PS1='\[\e[1;33m\][ujima-dev]\[\e[0m\] \[\e[0;32m\][ro]\[\e[0m\] \u@\h:\w\$ '
else
    PS1='\[\e[1;33m\][ujima-dev]\[\e[0m\] \[\e[1;31m\][rw]\[\e[0m\] \u@\h:\w\$ '
fi
