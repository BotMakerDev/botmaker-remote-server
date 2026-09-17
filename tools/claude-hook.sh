#!/usr/bin/env bash
#
# claude-hook.sh — tell botmaker-remote-server what Claude Code just did in this tmux window.
#
# Wired as a Claude Code hook (see README.md › Hooks). Claude Code runs it with the hook's JSON on stdin;
# the only fields read are `hook_event_name` and, for a Notification, `message`. The tmux window index is
# read off $TMUX_PANE, which Claude inherits from the shell tmux started — so a Claude that is not running
# under tmux (a desktop terminal, an IDE) exits 0 having sent nothing, which is the right answer: there is
# no window for a phone to open.
#
# Never fails the hook: a server that is down must not make Claude Code report a hook error on every turn.

set -u

[ -n "${TMUX_PANE:-}" ] || exit 0
command -v tmux >/dev/null 2>&1 || exit 0

port="${BOTMAKER_REMOTE_PORT:-7788}"
token_file="${BOTMAKER_REMOTE_TOKEN_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/botmaker/remote/token}"
[ -r "$token_file" ] || exit 0
token="$(head -n1 "$token_file")"

# The server binds the tailnet address; ask tailscale for it rather than guessing, and fall back to the
# loopback for a --bind 127.0.0.1 server.
host="$(ip -4 -o addr show tailscale0 2>/dev/null | sed -n 's/.*inet \([0-9.]*\)\/.*/\1/p' | head -n1)"
[ -n "$host" ] || host="127.0.0.1"

window="$(tmux display-message -p -t "$TMUX_PANE" '#{window_index}' 2>/dev/null)" || exit 0
[ -n "$window" ] || exit 0

input="$(cat 2>/dev/null || true)"
event="$(printf '%s' "$input" | sed -n 's/.*"hook_event_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
message="$(printf '%s' "$input" | sed -n 's/.*"message"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
[ -n "$event" ] || event="${1:-Stop}"

# A JSON body with the message escaped the only two ways it can break a string.
message="${message//\\/\\\\}"
message="${message//\"/\\\"}"

curl -s -m 3 -o /dev/null \
  -H "X-Botmaker-Token: $token" \
  -H "Content-Type: application/json" \
  -d "{\"event\":\"$event\",\"window\":$window,\"message\":\"$message\"}" \
  "http://$host:$port/api/hook" || true
exit 0
