# Changelog

All notable changes to `botmaker-remote-server`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this module uses
[semantic versioning](https://semver.org/). `release.sh` refuses to cut a version with no section here.

## [Unreleased]

### Added

- **`sudo dnf install botmaker-remote-server`** — a dnf and an apt repository on GitHub Pages
  (`.github/scripts/build-repo.sh`, `botmaker-cli`'s script with the names changed), so the server arrives
  and updates with the rest of the system rather than by downloading a file. The repository carries the
  latest release only; the signing key is the one `botmaker-cli`'s repository already publishes. A separate
  index from the cli's rather than one serving both: a Pages site is built from the artifacts of the
  release that triggered it, so a shared index would make two repositories able to break each other.
  **It is published unsigned for now**, and says so: the `.repo` sets `gpgcheck=0`/`repo_gpgcheck=0`, the
  apt line is `[trusted=yes]`, and the page states that nothing verifies where a package came from. Setting
  `GPG_KEY_ID`, `GPG_PASSPHRASE` and `GPG_PRIVATE_KEY` on the repository signs the rpm and both indexes on
  the next release and switches every snippet to the verified form; nothing else changes.
- **An rpm and a deb** (`packaging/nfpm.yaml`, one description for both, like `botmaker-cli`'s):
  installing the package puts `/usr/bin/botmaker-remote-server`,
  `/usr/bin/botmaker-remote-hook`, the jar under `/usr/share/botmaker/` and a systemd **user** unit on the
  machine, and the jar then updates with the system. Installing starts nothing — `systemctl --user enable
  --now botmaker-remote` is where a person consents to it listening, and it runs as them because it needs
  their PATH, their `~/.claude` and their tmux server. `java-25-openjdk-headless` and `tmux` are hard
  dependencies; `cswap` and `claude` are per-user installs no distribution ships.
- **`botmaker-remote-server` is a command now** (`packaging/botmaker-remote-server`), on `PATH` from either
  installation. It resolves the jar itself — `$BOTMAKER_REMOTE_JAR`, then `~/.local/lib/botmaker/`, then
  `/usr/share/botmaker/` — which is what lets one systemd unit serve the package and `tools/install.sh`
  alike, and lets a local build shadow the packaged jar with nothing uninstalled.
- **The server.** One executable jar that serves the tmux session `claude` — one window per Claude
  account, each `cswap run <slot> -- claude` — to a phone over the Tailscale address: the window list
  with a `running` / `waiting` / `idle` state, opening and closing windows, typing into one without
  opening it, and the terminal itself as a PTY over WebSocket. Every request carries a pairing token
  created once in `~/.config/botmaker/remote/token` and printed as a QR code at start (`--big-qr` for
  consoles that pad their lines, such as an IDE run window).
- **Binds the tailnet or nothing.** Without a `tailscale0` address the server refuses to start; `--bind`
  is the typed override.
- **Hooks.** `tools/claude-hook.sh` reports Claude Code's `Stop`, `Notification` and `UserPromptSubmit`
  hooks with the tmux window index, which is what tells a *waiting* Claude from a working one. A
  `--ntfy` topic forwards every *waiting* event as a phone notification.
- **A view per phone.** The terminal attaches through a grouped tmux session so a phone opening window 2
  does not swap the desktop terminal to window 2; the view is destroyed on detach.
- `tools/install.sh` and a systemd user unit.
