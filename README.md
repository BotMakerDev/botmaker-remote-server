# botmaker-remote-server

Phone access to the Claude Code terminals on a dev box, over Tailscale. The server half of
[botmaker-remote](https://github.com/BotMakerDev/botmaker-remote), the phone app.

**The problem it answers.** Claude Code's Remote Control binds one Claude account; switching accounts with
[`cswap`](https://github.com/LiQiyeDev/cswap) ends the session. So the phone attaches to *terminals*
instead: one tmux window per account, each running `cswap run <slot> -- claude`, and the phone opens
whichever it likes. Nothing switches, so nothing disconnects.

**What it is.** One executable jar. It serves, on the machine's Tailscale address only:

| Route | What |
|---|---|
| `GET /api/sessions` | the tmux windows in session `claude`, each with `running` / `waiting` / `idle` |
| `POST /api/sessions {slot}` | open a window running Claude under that `cswap` account |
| `DELETE /api/sessions/{i}` | close one |
| `POST /api/sessions/{i}/send {text}` or `{key}` | type into one without opening it (quick replies) |
| `GET /api/accounts` | the `cswap` slots and their usage |
| `WS /ws/term/{i}?cols=&rows=` | the terminal itself: a PTY on `tmux attach`, bytes both ways |
| `WS /ws/events` | every state change, as JSON |
| `POST /api/hook` | where Claude Code's own hooks report *turn finished* / *needs an answer* |

Every request carries the pairing token (`X-Botmaker-Token`, `Authorization: Bearer`, or `?token=` on a
WebSocket). The token is created once in `~/.config/botmaker/remote/token` (`0600`) and is in the pairing
URL the server prints, as a QR code, when it starts.

## Security, in one paragraph

This is a shell on your machine. The server therefore **binds the `tailscale0` address and refuses to
start without one**: on the tailnet, the token is a second lock behind WireGuard and your tailnet's ACLs.
`--bind <ip>` overrides that, and it takes a full address on purpose — typing `0.0.0.0` is a decision, and
it should be typed. Never expose it through Tailscale Funnel.

## Install

**Fedora, RHEL, openSUSE** — add the repository once, then it updates with the rest of the system:

```bash
sudo curl -fsSL -o /etc/yum.repos.d/botmaker-remote-server.repo \
  https://botmakerdev.github.io/botmaker-remote-server/botmaker-remote-server.repo
sudo dnf install botmaker-remote-server
systemctl --user enable --now botmaker-remote
journalctl --user -u botmaker-remote -f          # the pairing URL is in the log
```

**Debian, Ubuntu** — the apt line depends on whether the release was signed, so take it from
[the repository page](https://botmakerdev.github.io/botmaker-remote-server/), which prints the snippet that
matches what is actually published.

**Signing.** The repository is **unsigned today**: nothing verifies that a package came from this project,
and `dnf` is told so — the generated `.repo` sets `gpgcheck=0` and `repo_gpgcheck=0`, and the apt line says
`[trusted=yes]`. HTTPS proves who *served* the file, not who *built* it. If that is not a trade you want,
install the release asset by hand, or `tools/install.sh` from a checkout. **The next release signs itself**:
`GPG_KEY_ID`, `GPG_PASSPHRASE` and `GPG_PRIVATE_KEY` are set on the `BotMakerDev` organization, which this
repository joined on 2026-09-18, and they hold the key `botmaker-cli` already publishes. With them present
the release job signs the rpm and both indexes, the page starts printing the verified snippets, and nothing
else changes. The steps are in
[`docs/signing.md`](docs/signing.md), including what an already-installed machine has to import when
`gpgcheck` goes on.

Later: `sudo dnf upgrade botmaker-remote-server` (or apt's equivalent), then `systemctl --user restart
botmaker-remote`. The repository carries the **latest release only** — it is an upgrade channel, not an
archive; every version stays on the Releases page, and
[`botmaker-remote-server.rpm`](https://github.com/BotMakerDev/botmaker-remote-server/releases/latest/download/botmaker-remote-server.rpm)
installs directly with `sudo dnf install ./botmaker-remote-server.rpm`.

The package installs `/usr/bin/botmaker-remote-server`, `/usr/bin/botmaker-remote-hook`, the jar under
`/usr/share/botmaker/` and a systemd **user** unit. It starts nothing: a server that hands out a shell
should listen because somebody enabled it, and it runs as *you* — it needs your PATH, your `~/.claude` and
your tmux server, which a root service would not have. `java` and `tmux` come with it; `tailscale` is
assumed, and `cswap` + `claude` are per-user installs the "new session" screen needs.

**Any Linux, no root** — the same four files under `~/.local`:

```bash
git clone https://github.com/BotMakerDev/botmaker-remote-server && cd botmaker-remote-server
tools/install.sh                 # newest release; or tools/install.sh target/…-all.jar for a local build
systemctl --user enable --now botmaker-remote   # install.sh does this for you
```

Both may be installed at once — the launcher prefers `~/.local/lib/botmaker/botmaker-remote-server-all.jar`,
so a local build shadows the packaged one without uninstalling anything, and `BOTMAKER_REMOTE_JAR=<path>`
beats both.

By hand instead: `java -jar botmaker-remote-server-all.jar [--port 7788] [--bind IP] [--token-file PATH]
[--ntfy URL] [--big-qr] [--quiet]` prints the QR and serves until killed. The QR uses half-block glyphs,
which an IDE run window (IntelliJ's console pads its lines) tears into stripes — `--big-qr` draws one
full block per module instead, square anywhere.

## Hooks — the "Claude is waiting" badge

A terminal cannot tell a thinking Claude from one waiting for you; Claude Code's hooks can. Add to
`~/.claude/settings.json`:

```json
{
  "hooks": {
    "Stop":             [{ "hooks": [{ "type": "command", "command": "botmaker-remote-hook" }] }],
    "Notification":     [{ "hooks": [{ "type": "command", "command": "botmaker-remote-hook" }] }],
    "UserPromptSubmit": [{ "hooks": [{ "type": "command", "command": "botmaker-remote-hook" }] }]
  }
}
```

The script reads the window index off `$TMUX_PANE`, so a Claude that is not under tmux sends nothing,
and never fails the hook when the server is down.

**Background notifications** need no push service of ours: install the [ntfy](https://ntfy.sh) app,
subscribe to a topic, and start the server with `--ntfy https://ntfy.sh/<topic>` (in the unit:
`Environment=ARGS=--quiet --ntfy https://ntfy.sh/<topic>`). Every *waiting* event lands as a notification.

## Pairing again

A phone that was wiped, a second phone, or a QR nobody scanned in time — the pairing code is not something
the running server has to be stopped to see:

```bash
botmaker-remote-server --pair              # the QR, the URL and the token file; binds nothing
journalctl --user -u botmaker-remote | grep -m1 '^pair:'    # the same URL, from the service's own log
```

`--pair` reads the address the server would bind (`tailscale0`, or `--bind`), the port and the token file,
and prints what a fresh start prints. Nothing is served, so it works while the service is running — which
is the case that matters, since the service holds the port.

**If a start refuses the port**, it now says who has it and what to do: `systemctl --user stop
botmaker-remote.service` to take it over, `--port N` to serve beside it, or `--pair` if the code was all
that was wanted.

The token itself is `~/.config/botmaker/remote/token`. Deleting it and restarting the server mints a new
one — every paired phone then has to scan again, which is how a leaked URL is revoked.

## Sessions

```bash
tmux new -d -s claude -n main 'cswap run 1 -- claude'       # one window per account, or
tmux new-window -t claude -n other 'cswap run 2 -- claude'    # … the app's ＋ button does the same
```

The phone attaches through a **grouped session** of its own (`tmux new-session -t claude`), so opening
window 2 on the phone does not swap the desktop terminal to window 2; the view is destroyed when the
phone detaches.

## Releasing

From the umbrella: `./release.sh --remote-server <version>`. The GitHub Release carries
`botmaker-remote-server-all.jar` under a stable name, which is what `tools/install.sh` downloads.
