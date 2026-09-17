# CLAUDE.md — botmaker-remote-server

Guidance for Claude Code working in this module. The umbrella's `CLAUDE.md` is the map; this is what is
true here.

## What it is

The server half of the phone app `botmaker-remote`: one executable jar that serves the tmux session
`claude` — one window per Claude account, each `cswap run <slot> -- claude` — to a phone over the
Tailscale address. `README.md` is the operator's document and lists the routes; this file is the rules.

**A program, not a library.** Nothing resolves it as a dependency, JitPack never builds it, and the GitHub
Release's `botmaker-remote-server-all.jar` (stable name — `tools/install.sh` downloads
`releases/latest/download/…`) is the artifact. It depends on nothing of ours: Javalin, pty4j, Jackson,
ZXing. Reactor position after `botmaker-cli`; released with `./release.sh --remote-server`.

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
