#!/usr/bin/env bash
# Forwards localhost:8090 to the server's loopback-only port. Keep it open while you use the API.
# Daily: run this, then open http://127.0.0.1:8090/auth/login in your browser to log in to Kite.
set -euo pipefail
: "${TALLY_HOST:?set TALLY_HOST=ubuntu@<static-ip>}"
SSH_OPTS=(-o ExitOnForwardFailure=yes -o ServerAliveInterval=30)
[[ -n "${TALLY_SSH_KEY:-}" ]] && SSH_OPTS+=(-i "$TALLY_SSH_KEY")
echo "Tunnel open: http://127.0.0.1:8090  (Ctrl-C to close)"
exec ssh -N -L 8090:127.0.0.1:8090 "${SSH_OPTS[@]}" "$TALLY_HOST"
