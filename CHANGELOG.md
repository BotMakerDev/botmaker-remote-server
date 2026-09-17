# Changelog

All notable changes to `botmaker-remote-server`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this module uses
[semantic versioning](https://semver.org/). `release.sh` refuses to cut a version with no section here.

## [Unreleased]

### Added

- **The server.** One executable jar that serves the tmux session `claude` — one window per Claude
  account, each `cswap run <slot> -- claude` — to a phone over the Tailscale address: the window list
  with a `running` / `waiting` / `idle` state, opening and closing windows, typing into one without
  opening it, and the terminal itself as a PTY over WebSocket. Every request carries a pairing token
  created once in `~/.config/botmaker/remote/token` and printed as a QR code at start.
- **Binds the tailnet or nothing.** Without a `tailscale0` address the server refuses to start; `--bind`
  is the typed override.
- **Hooks.** `tools/claude-hook.sh` reports Claude Code's `Stop`, `Notification` and `UserPromptSubmit`
  hooks with the tmux window index, which is what tells a *waiting* Claude from a working one. A
  `--ntfy` topic forwards every *waiting* event as a phone notification.
- **A view per phone.** The terminal attaches through a grouped tmux session so a phone opening window 2
  does not swap the desktop terminal to window 2; the view is destroyed on detach.
- `tools/install.sh` and a systemd user unit.
