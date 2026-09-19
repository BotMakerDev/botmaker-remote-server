# Changelog

All notable changes to `botmaker-remote-server`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this module uses
[semantic versioning](https://semver.org/). `release.sh` refuses to cut a version with no section here.

## [Unreleased]

### Changed

- The refusal to start without a `tailscale0` address now says when tailscaled still reports one
  (`tailscale ip -4`): the daemon lost its interface configuration, usually after starting while the
  network was down, and `sudo systemctl restart tailscaled` restores it. The server still binds the
  tailnet or nothing.

## [0.0.6] — 2026-09-19

### Changed

- **The package page looks like the rest of the project, and its commands copy.** The stylesheet and the
  copy button come from `botmakerdev.github.io/assets/`, the organization's front page — the same origin
  this site is served from — rather than from a `<style>` block repeated in four repositories, and the page
  links to that front page, where one command installs every BotMaker tool at once. The unsigned-repository
  notice, the repository itself and the snippets are untouched.

## [0.0.5] — 2026-09-19

### Changed

- **`docs/signing.md` leads with `tools/signing-secret.sh`**, including the organization-wide form that
  sets the three secrets once for every repository, and keeps the by-hand Seahorse export underneath as
  the explanation of what the script does.
- **Every address is `BotMakerDev`'s**: the dnf/apt repository is served from
  `botmakerdev.github.io/botmaker-remote-server`, and the README's clone, release-asset and phone-app links
  name the organization the repository moved into on 2026-09-18. The signing secrets are the
  organization's now, so this repository signs its next release without a step of its own.

## [0.0.4] — 2026-09-18

### Added

- **`--pair` prints the pairing QR without binding anything.** The packaged unit runs `--quiet` and holds
  port 7788, so the only way to see a QR code was to stop the very server the code pairs with. The QR is a
  function of the address, the port and the token file — never of a socket — so `--pair` reads those three
  and prints the same block a fresh start prints.

### Changed

- **`docs/signing.md` says how this repository gets signed**, with the key `botmaker-cli`'s Pages site
  already publishes (`5300F1BC092474AC`): the three secrets, how to export the key without it passing
  through a terminal, the re-run that republishes the site, and what an already-installed machine must
  import once `gpgcheck` goes on. No workflow changed — it takes the signed branch the moment the secrets
  exist.
- **A refused bind is a sentence.** `Could not bind <address>:<port>`, who holds it (`ss -ltnp`, so
  `java (pid 247245)` rather than nothing), and the three ways out: stop the service, `--port N`, or
  `--pair`. Jetty's stack trace named neither the port nor the program.
- **`--quiet` keeps the `pair:` line** and drops the QR block only, so
  `journalctl --user -u botmaker-remote` is a second way to pair again.
- **The release and Pages jobs run on the Node 24 action majors** (`checkout@v7`, `setup-java@v6`,
  `upload-artifact@v7`, `download-artifact@v8`, `configure-pages@v6`, `upload-pages-artifact@v5`,
  `deploy-pages@v5`). GitHub was forcing the Node 20 versions onto Node 24 and `setup-java@v4` no longer
  receives updates. No input changed; `download-artifact@v8` now fails a download whose digest does not
  match, which is the verdict the rpm and the deb want.

## [0.0.3] — 2026-09-17

No source changes since v0.0.2; re-released for updated upstream pins.

No source changes since v0.0.1; re-released for updated upstream pins.

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

## [0.0.2] — 2026-09-17

No source changes since v0.0.1; re-released for updated upstream pins.

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

## [0.0.1] — 2026-09-17

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
