#!/usr/bin/env bash
#
# install.sh [jar] — install the server, its launcher, the unit and the hook under ~/.local, for one user.
#
# With no argument, downloads the newest release's jar; with one, copies that file (a local build:
# target/botmaker-remote-server-0.0.0-SNAPSHOT-all.jar). Idempotent: run it again to update.
#
# On Fedora or any dnf/apt machine there is a package instead — `botmaker-remote-server.rpm` /`.deb` on the
# GitHub Release — and it installs the same four files system-wide. This script stays for the case the
# package cannot serve: a build from a checkout, and a machine where the person has no root. Both may be
# installed at once; the launcher prefers this jar, so a local build shadows the packaged one.

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
    https://github.com/BotMakerDev/botmaker-remote-server/releases/latest/download/botmaker-remote-server-all.jar
fi
cp "$here/claude-hook.sh" "$bin/botmaker-remote-hook"
chmod +x "$bin/botmaker-remote-hook"
# The unit runs `botmaker-remote-server` off PATH, so the launcher is part of the installation rather than
# a nicety — and having it on PATH is also what makes running the server by hand the same command as the
# service's.
cp "$here/../packaging/botmaker-remote-server" "$bin/botmaker-remote-server"
chmod +x "$bin/botmaker-remote-server"
cp "$here/botmaker-remote.service" "$units/botmaker-remote.service"

systemctl --user daemon-reload
systemctl --user enable --now botmaker-remote
echo "installed. Pairing URL:"
sleep 3
journalctl --user -u botmaker-remote -n 20 --no-pager | grep -m1 'pair: ' || echo "  (not up yet — journalctl --user -u botmaker-remote -f)"
echo
echo "Now add the hooks to ~/.claude/settings.json (README.md › Hooks) and start a session:"
echo "  tmux new -d -s claude -n <account> 'cswap run <slot> -- claude'"
