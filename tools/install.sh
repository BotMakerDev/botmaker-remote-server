#!/usr/bin/env bash
#
# install.sh [jar] — put the server where the systemd unit expects it, install the unit and the hook.
#
# With no argument, downloads the newest release's jar; with one, copies that file (a local build:
# target/botmaker-remote-server-0.0.0-SNAPSHOT-all.jar). Idempotent: run it again to update.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
lib="$HOME/.local/lib/botmaker"
bin="$HOME/.local/bin"
units="$HOME/.config/systemd/user"
mkdir -p "$lib" "$bin" "$units"

if [ $# -ge 1 ]; then
  cp "$1" "$lib/botmaker-remote-server-all.jar"
else
  curl -fsSL -o "$lib/botmaker-remote-server-all.jar" \
    https://github.com/LiQiyeDev/botmaker-remote-server/releases/latest/download/botmaker-remote-server-all.jar
fi
cp "$here/claude-hook.sh" "$bin/botmaker-remote-hook"
chmod +x "$bin/botmaker-remote-hook"
cp "$here/botmaker-remote.service" "$units/botmaker-remote.service"

systemctl --user daemon-reload
systemctl --user enable --now botmaker-remote
echo "installed. Pairing URL:"
sleep 3
journalctl --user -u botmaker-remote -n 20 --no-pager | grep -m1 'pair: ' || echo "  (not up yet — journalctl --user -u botmaker-remote -f)"
echo
echo "Now add the hooks to ~/.claude/settings.json (README.md › Hooks) and start a session:"
echo "  tmux new -d -s claude -n <account> 'cswap run <slot> -- claude'"
