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

**The unit names the command, never a jar.** `packaging/botmaker-remote-server` resolves the jar
(`$BOTMAKER_REMOTE_JAR` → `~/.local/lib/botmaker/` → `/usr/share/botmaker/`), so the package and
`tools/install.sh` share **one** `botmaker-remote.service` — a second copy of a unit is two descriptions of
one service. It is a **user** unit and the package enables nothing: this program hands out a shell and runs
`cswap`/`claude` as the operator, so it is their process and their decision to start it.

## The rules

- **Binds the tailnet or nothing** (`Tailnet`). This is a shell on the operator's machine; the token is a
  second lock behind WireGuard, never the only one. `--bind` is the typed override. Never add Funnel.
- **tmux is the truth, nothing is cached.** `Tmux.list()` runs `tmux` every time a screen is drawn; the
  only state held is `Activity` (the last hook per window), and losing it costs a badge.
- **A phone attaches to a view, not to the session** (`Tmux.openView`): a grouped session with its own
  current window, `destroy-unattached` set *after* attaching (set before, tmux destroys it on the spot).
- **The wire interprets nothing** (`Terminal`): bytes both ways; xterm.js on the phone is the terminal.
- **Every route wants the token** (`Routes.tokenOf`), including the hook — the hook script reads it off
  the same file.
- Tests spawn no process. `ParsingTest` holds the three text formats read (tmux, cswap, ip);
  `GuardsTest` the token and the activity state. Anything that needs tmux is checked by hand with the
  WebSocket script in the phase recap, not by a test that fails on CI for lack of a terminal.

## Building

```bash
mvn verify                                   # here
java -jar target/botmaker-remote-server-0.0.0-SNAPSHOT-all.jar --port 7799 --token-file /tmp/t --quiet
```

Pins: `maven-compiler-plugin` 3.11.0, `maven-shade-plugin` 3.5.1 — the same set as the rest of the
reactor, so one fact is kept once.
