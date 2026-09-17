# CLAUDE.md — botmaker-remote-server

Guidance for Claude Code working in this module. The umbrella's `CLAUDE.md` is the map; this is what is
true here.

## What it is

The server half of the phone app `botmaker-remote`: one executable jar that serves the tmux session
`claude` — one window per Claude account, each `cswap run <slot> -- claude` — to a phone over the
Tailscale address. `README.md` is the operator's document and lists the routes; this file is the rules.

**A program, not a library.** Nothing resolves it as a dependency, JitPack never builds it, and the GitHub
Release's three assets — `botmaker-remote-server-all.jar`, `.rpm`, `.deb`, all unversioned names — are the
artifact. It depends on nothing of ours: Javalin, pty4j, Jackson, ZXing. Reactor position after
`botmaker-cli`; released with `./release.sh --remote-server`.

**Packaged with nfpm, not jpackage** (`packaging/nfpm.yaml`) — `botmaker-cli`'s argument, and its rpm
signing rules: a headless jar with no desktop presence gains nothing from a bundled runtime, and dnf checks
a package's own signature where apt trusts a signed index. The dnf/apt repository on Pages
(`.github/scripts/build-repo.sh`) is that repository's script with the names changed; **the reasoning lives
there and is not copied here**. It is its own index rather than a shared one, because a Pages site is built
from the artifacts of the release that triggered it.

**The repository is unsigned, by decision, and every snippet it publishes says so** (2026-09-17). Signing
is three secrets away and the script takes the other branch the moment they exist — what must not happen is
the shape the cli hit: checks turned off while the page still reads as if something was verified. If you
add signing, change nothing else; if you keep it unsigned, keep the notice. **`docs/signing.md` is the
procedure** — the same key `botmaker-cli` already publishes, set by the maintainer from their own keyring;
no assistant session ever reads a secret value, and none of the workflow changes when they appear.

**The unit names the command, never a jar.** `packaging/botmaker-remote-server` resolves the jar
(`$BOTMAKER_REMOTE_JAR` → `~/.local/lib/botmaker/` → `/usr/share/botmaker/`), so the package and
`tools/install.sh` share **one** `botmaker-remote.service` — a second copy of a unit is two descriptions of
one service. It is a **user** unit and the package enables nothing: this program hands out a shell and runs
`cswap`/`claude` as the operator, so it is their process and their decision to start it.

## The rules

- **Binds the tailnet or nothing** (`Tailnet`). This is a shell on the operator's machine; the token is a
  second lock behind WireGuard, never the only one. `--bind` is the typed override. Never add Funnel.
- **The QR is a function of the address, the port and the token file — never of a socket** (`--pair`,
  2026-09-17). The packaged unit runs `--quiet` and holds the port, so a question that needed the server
  stopped to be answered was the one question it must not need. `--quiet` suppresses the QR block and keeps
  the `pair:` line, so the journal answers it too. A refused bind names its holder (`Ports`) and the three
  ways out, because Jetty's own failure names neither the port nor the program.
- **tmux is the truth, nothing is cached.** `Tmux.list()` runs `tmux` every time a screen is drawn; the
  only state held is `Activity` (the last hook per window), and losing it costs a badge.
- **A phone attaches to a view, not to the session** (`Tmux.openView`): a grouped session with its own
  current window, `destroy-unattached` set *after* attaching (set before, tmux destroys it on the spot).
- **The wire interprets nothing** (`Terminal`): bytes both ways; xterm.js on the phone is the terminal.
- **Every route wants the token** (`Routes.tokenOf`), including the hook — the hook script reads it off
  the same file.
- Tests spawn no process. `ParsingTest` holds the four text formats read (tmux, cswap, ip, `ss`) and the
  option parsing;
  `GuardsTest` the token and the activity state. Anything that needs tmux is checked by hand with the
  WebSocket script in the phase recap, not by a test that fails on CI for lack of a terminal.

## Building

```bash
mvn verify                                   # here
java -jar target/botmaker-remote-server-0.0.0-SNAPSHOT-all.jar --port 7799 --token-file /tmp/t --quiet
```

Pins: `maven-compiler-plugin` 3.11.0, `maven-shade-plugin` 3.5.1 — the same set as the rest of the
reactor, so one fact is kept once.
